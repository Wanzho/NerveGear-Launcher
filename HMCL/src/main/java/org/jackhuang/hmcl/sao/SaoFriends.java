/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.collections.*;
import org.jetbrains.annotations.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/// ARGUS social engine: finite resumable SSE, polling fallback, idempotent sends and JavaFX-only publications.
@NotNullByDefault
final class SaoFriends implements AutoCloseable {
    /// Ephemeral authorization handed over by the existing account state machine; never persisted.
    @NotNullByDefault
    record Session(URI root,String token,String id,String name) {
        /// Keeps bearer and player fields out of diagnostics.
        @Override public String toString(){return "SocialSession[redacted]";}
    }
    /// Checked social operation returning JSON on the serialized API worker.
    @FunctionalInterface @NotNullByDefault private interface Action { /// Executes one bounded request.
        JsonObject run(Session session)throws Exception; }
    /// Existing bounded, nonredirecting JSON transport and Chinese error mapper.
    private final Transport client;
    /// Minimal issuing-origin-pinned transport seam, allowing unit tests without any listening sockets.
    @NotNullByDefault
    interface Transport {
        /// Current configured endpoint used by the generation/origin fence.
        URI root();
        /// Bounded request pinned to the captured issuing origin, with an ephemeral bearer.
        JsonObject jsonAt(URI origin,String method,String route,@Nullable JsonObject body,@Nullable String bearer)throws SaoAccountException;
    }
    /// Installation-owned data directory; only nonsecret device ID and cursor are persisted.
    private final Path root;
    /// Fixed short errors for void seam methods.
    private final Consumer<String> errors;
    /// Serialized mutations, history and publication bookkeeping, separate from account login work.
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"argus-social-api");t.setDaemon(true);return t;});
    /// Reconnection loop owns a single stream; it cannot block mutations or JavaFX.
    private final ExecutorService live=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"argus-social-live");t.setDaemon(true);return t;});
    /// Live transport refuses all redirects, including same-origin redirects.
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    /// Count of successfully opened SSE connections, containing no player or message data.
    final AtomicLong liveConnections=new AtomicLong();
    /// Current subscription body, closed immediately on signout, endpoint switch or process shutdown.
    private final AtomicReference<@Nullable InputStream> input=new AtomicReference<>();
    /// Stable screen-facing lists; records are replaced, never modified in place.
    private final ObservableList<SaoData.Friend> friends=FXCollections.observableArrayList();
    /// Retained wrapper keeps the frontend listener alive for the engine lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Friend> friendsView=FXCollections.unmodifiableObservableList(friends);
    /// Both incoming and outgoing pending friend requests.
    private final ObservableList<SaoData.FriendRequest> requests=FXCollections.observableArrayList();
    /// Stable read-only request wrapper.
    private final @UnmodifiableView ObservableList<SaoData.FriendRequest> requestsView=FXCollections.unmodifiableObservableList(requests);
    /// Latest private conversation snapshots, newest first.
    private final ObservableList<SaoData.Chat> chats=FXCollections.observableArrayList();
    /// Stable read-only conversation wrapper.
    private final @UnmodifiableView ObservableList<SaoData.Chat> chatsView=FXCollections.unmodifiableObservableList(chats);
    /// Stable message-list identities retained by screen listeners across live updates.
    private final Map<String,ObservableList<SaoData.ChatMessage>> views=new ConcurrentHashMap<>();
    /// Retained read-only message wrappers keep screen listeners attached across getter calls.
    private final Map<String,@UnmodifiableView ObservableList<SaoData.ChatMessage>> publicViews=new ConcurrentHashMap<>();
    /// Ordered delivered messages, keyed by server ID, owned only by the API worker.
    private final Map<String,NavigableMap<Long,JsonObject>> messages=new HashMap<>();
    /// Pending sends are keyed by a client nonce which survives retries in this session.
    private final Map<String,JsonObject> pending=new ConcurrentHashMap<>();
    /// Older-history cursor per open conversation, owned by the API worker.
    private final Map<String,Long> older=new HashMap<>();
    /// Authority visibility revisions fence cached content after blocks and membership changes.
    private final Map<String,Long> visibility=new HashMap<>();
    /// Prevents redundant simultaneous history fetches for one conversation.
    private final Set<String> loading=ConcurrentHashMap.newKeySet();
    /// Local transient coalescing mirrors the authority's three-second per-chat typing bound.
    private final Map<String,Long> lastTyping=new HashMap<>();
    /// Current authorization, replaced atomically before stale publications can apply.
    private volatile @Nullable Session session;
    /// Stable random installation ID, namespaced with the profile for server device watermarks.
    private final String device;
    /// Last applied live cursor, persisted without any chat body or bearer.
    private volatile long cursor;
    /// Current game process report; dedicated-server presence always overrides it on the server.
    private volatile boolean playing;
    /// Version fence cancels queued work after account switching or signout.
    private final AtomicLong generation=new AtomicLong();
    /// Outstanding seam futures are failed on account switches and shutdown, including queued work.
    private final Set<CompletableFuture<JsonObject>> futures=ConcurrentHashMap.newKeySet();
    /// Stops both daemon workers and fails new requests on close.
    private final AtomicBoolean closed=new AtomicBoolean();

    /// Creates a dormant social engine; no network request occurs before a successful ARGUS sign-in.
    SaoFriends(Path root,SaoAccountClient client,Consumer<String> errors){this(root,new Transport(){
        /// Reads the production transport's validated current origin.
        @Override public URI root(){return client.root();}
        /// Preserves the production transport's bounded/no-redirect credential handling.
        @Override public JsonObject jsonAt(URI origin,String method,String route,@Nullable JsonObject body,@Nullable String bearer)throws SaoAccountException{return client.jsonAt(origin,method,route,body,bearer);}
    },errors);}
    /// Injectable unit-test constructor; constructing the engine never starts network work.
    SaoFriends(Path root,Transport client,Consumer<String> errors){this.root=root;this.client=client;this.errors=errors;
        String id=UUID.randomUUID().toString();Path file=root.resolve("social-device");
        try{Files.createDirectories(root);if(Files.isRegularFile(file)&&Files.size(file)<100)id=Files.readString(file).strip();else Files.writeString(file,id,StandardOpenOption.CREATE_NEW);if(!id.matches("[A-Za-z0-9_-]{8,80}"))throw new IOException();}
        catch(IOException failure){id=UUID.randomUUID().toString();}device=id;
    }
    /// Publishes a stable read-only friends view.
    @UnmodifiableView ObservableList<SaoData.Friend> friends(){return friendsView;}
    /// Publishes both directions of pending requests.
    @UnmodifiableView ObservableList<SaoData.FriendRequest> requests(){return requestsView;}
    /// Publishes live conversation metadata and unread counts.
    @UnmodifiableView ObservableList<SaoData.Chat> chats(){return chatsView;}
    /// Returns the stable oldest-first list, loading the newest history page on first access.
    @UnmodifiableView ObservableList<SaoData.ChatMessage> messages(String id){var view=views.computeIfAbsent(id,k->FXCollections.observableArrayList());loadOlder(id);return publicViews.computeIfAbsent(id,k->FXCollections.unmodifiableObservableList(view));}
    /// Loads one older page when the screen asks again; the seam has no explicit scroll/page callback.
    void loadOlder(String id){if(session==null||!loading.add(id))return;submit(s->{long before=older.getOrDefault(id,0L);if(before==-1)return new JsonObject();var page=client.jsonAt(s.root(),"GET","sao/social/history?chat="+id+"&before="+before+"&limit=50",null,s.token());for(var e:page.getAsJsonArray("messages"))remember(e.getAsJsonObject());long next=page.get("nextBefore").getAsLong();older.put(id,next==0?-1:next);publishMessages(s,id);return page;}).whenComplete((v,e)->loading.remove(id));}
    /// Starts/replaces subscription authorization; old-account publications are fenced before any clear is queued.
    synchronized void session(@Nullable Session next){if(Objects.equals(session,next))return;session=next;long epoch=generation.incrementAndGet();publish(()->futures.forEach(f->f.completeExceptionally(new SaoAccountException("账号操作已取消","cancelled"))));closeInput();cursor=0;pending.clear();
        execute(()->{messages.clear();older.clear();visibility.clear();loading.clear();lastTyping.clear();if(next!=null)cursor=readCursor(next);publish(()->{if(epoch!=generation.get())return;friends.clear();requests.clear();chats.clear();views.values().forEach(List::clear);});});
        if(next!=null&&!closed.get())live.execute(()->stream(next,epoch));
    }
    /// Sets process presence and rotates the stream immediately so friends receive the new state.
    void playing(boolean value){playing=value;closeInput();}
    /// Sends one friend/group operation, then refreshes authoritative state before completing on JavaFX.
    CompletableFuture<JsonObject> operation(String action,JsonObject body){return submit(s->{var result=client.jsonAt(s.root(),"POST","sao/social/"+action,body,s.token());if(result.has("id")&&result.has("sender"))remember(result);apply(s,client.jsonAt(s.root(),"GET","sao/social/state",null,s.token()));return result;});}
    /// Shows a pending record immediately, then retries an ambiguous send with the identical nonce.
    CompletableFuture<Void> send(String id,String text){return send(id,text,0,false);}
    /// Sends text/reply/card with one session nonce and one retry for an ambiguous network response.
    CompletableFuture<Void> send(String id,String text,long replyTo,boolean invite){@Nullable Session s=session;if(s==null)return failed("请先登录 ARGUS ID","signedOut");
        String nonce=UUID.randomUUID().toString();var request=object("chat",id,"nonce",nonce);if(!invite)request.addProperty("text",text);if(replyTo>0)request.addProperty("replyTo",replyTo);
        var body=request.deepCopy();body.addProperty("text",invite?s.name()+" 邀请你一起 Link Start":text);body.addProperty("time",System.currentTimeMillis());body.addProperty("from",s.name());pending.put(nonce,body);publish(()->{if(session==s)rebuildView(s,id);});
        return submit(active->{JsonObject response;String route="sao/social/"+(invite?"playInvite":"send");try{response=client.jsonAt(active.root(),"POST",route,request,active.token());}catch(SaoAccountException failure){if(!failure.code().equals("network"))throw failure;response=client.jsonAt(active.root(),"POST",route,request,active.token());}remember(response);pending.remove(nonce);publishMessages(active,id);apply(active,client.jsonAt(active.root(),"GET","sao/social/state",null,active.token()));return response;}).handle((v,failure)->{
            if(failure!=null){pending.remove(nonce);publish(()->{if(session==s)rebuildView(s,id);});throw new CompletionException(failure);}return null;
        });
    }
    /// Uses the server message identity rather than trusting the card's display target or local clock alone.
    CompletableFuture<String> acceptInvite(SaoData.ChatMessage message,List<String> known){return submit(s->{if(message.kind()!=SaoData.MessageKind.INVITE||message.pending()||message.id()<=0)throw new SaoAccountException("邀请的服务器不可用","inviteTarget");var body=object("chat",message.chat());body.addProperty("message",message.id());var result=client.jsonAt(s.root(),"POST","sao/social/acceptInvite",body,s.token());result.addProperty("target",SaoSocialProtocol.target(result.get("target").getAsString(),known));return result;}).thenApply(v->v.get("target").getAsString());}
    /// Coalesces local keystrokes; rate-limit responses from other devices remain explicit fixed failures.
    CompletableFuture<Void> typing(String id){return submit(s->{long now=System.currentTimeMillis();if(now-lastTyping.getOrDefault(id,0L)<3000)return new JsonObject();lastTyping.put(id,now);return client.jsonAt(s.root(),"POST","sao/social/typing",object("chat",id),s.token());}).thenApply(v->null);}
    /// Returns immutable search results without adding them to delivery cursors, notifications or read state.
    CompletableFuture<@Unmodifiable List<SaoData.ChatMessage>> search(String text,String chat,int limit){return submit(s->{var body=object("text",text);if(!chat.isEmpty())body.addProperty("chat",chat);body.addProperty("limit",limit);return client.jsonAt(s.root(),"POST","sao/social/search",body,s.token());}).thenApply(v->{var list=new ArrayList<SaoData.ChatMessage>();v.getAsJsonArray("messages").forEach(m->list.add(SaoSocialProtocol.message(m.getAsJsonObject())));return List.copyOf(list);});}
    /// Marks the messages already fetched when the screen explicitly reports the conversation open.
    void markRead(String id){submit(s->{mark(s,id);return new JsonObject();}).exceptionally(failure->{errors.accept(publicMessage(failure));return null;});}
    /// Uses only messages already applied by this engine, protecting arrivals racing the read call.
    private void mark(Session s,String id)throws SaoAccountException{long through=messages.getOrDefault(id,new TreeMap<>()).isEmpty()?0:messages.get(id).lastKey();var body=object("chat",id);body.addProperty("through",through);client.jsonAt(s.root(),"POST","sao/social/read",body,s.token());}
    /// Runs the finite SSE reconnect loop, resuming from the durable per-profile cursor.
    private void stream(Session s,long epoch){int failures=0;while(valid(s,epoch)){
        try{
            String route="sao/social/events?device="+device+"&after="+cursor+"&game="+playing;
            HttpRequest request=HttpRequest.newBuilder(s.root().resolve(route)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+s.token()).header("Last-Event-ID",Long.toString(cursor)).header("Accept","text/event-stream").GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()){
                input.set(body);if(!valid(s,epoch))break;if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))throw new IOException();
                liveConnections.incrementAndGet();
                var line=new ByteArrayOutputStream();int n;while(valid(s,epoch)&&(n=body.read())!=-1){if(n=='\n'){String value=line.toString(StandardCharsets.UTF_8);line.reset();if(value.startsWith("data: ")){JsonObject batch=JsonParser.parseString(value.substring(6)).getAsJsonObject();submitBatch(s,epoch,batch).get(15,TimeUnit.SECONDS);failures=0;}}else{if(line.size()>1024*1024)throw new IOException();line.write(n);}}
            }finally{input.set(null);}
            failures=0;
        }catch(Exception unavailable){if(!valid(s,epoch))break;failures++;try{var batch=client.jsonAt(s.root(),"GET","sao/social/poll?device="+device+"&after="+cursor+"&game="+playing,null,s.token());submitBatch(s,epoch,batch).get(15,TimeUnit.SECONDS);}catch(Exception ignored){}}
        if(valid(s,epoch))try{Thread.sleep(failures>0?Math.min(10000,1000L<<Math.min(failures,3)):200);}catch(InterruptedException ended){Thread.currentThread().interrupt();break;}
    }}
    /// Serializes live batches with mutations/history, then persists and ACKs only after JavaFX application.
    private CompletableFuture<JsonObject> submitBatch(Session s,long epoch,JsonObject batch){return submit(active->{if(active!=s||!valid(s,epoch))throw new SaoAccountException("账号操作已取消","cancelled");for(var e:batch.getAsJsonArray("messages"))remember(e.getAsJsonObject());apply(s,batch);long next=batch.get("cursor").getAsLong();
        var applied=new CompletableFuture<Void>();publish(()->{if(valid(s,epoch))applied.complete(null);else applied.completeExceptionally(new CancellationException());});applied.get(10,TimeUnit.SECONDS);
        if(!valid(s,epoch))throw new CancellationException();if(next!=cursor){writeCursor(s,next);cursor=next;var ack=object("device",device);ack.addProperty("cursor",next);client.jsonAt(s.root(),"POST","sao/social/ack",ack,s.token());}return batch;
    });}
    /// Retains each message ID exactly once and reconciles its pending nonce, including ambiguous send retries.
    private void remember(JsonObject message){String chat=message.get("chat").getAsString();var stored=messages.computeIfAbsent(chat,k->new TreeMap<>());long id=message.get("id").getAsLong();@Nullable JsonObject prior=stored.get(id);if(prior!=null&&SaoSocialProtocol.number(prior,"revision")>SaoSocialProtocol.number(message,"revision"))return;stored.put(id,message.deepCopy());pending.remove(message.get("nonce").getAsString());
        // A recall also replaces any cached quoted plaintext from a source outside the loaded history page.
        long quoted=SaoSocialProtocol.number(message,"replyTo");@Nullable JsonObject source=stored.get(quoted);if(source!=null&&"RECALLED".equals(SaoSocialProtocol.string(source,"kind"))&&message.has("quote"))stored.get(id).getAsJsonObject("quote").addProperty("text",source.get("text").getAsString());
        if("RECALLED".equals(SaoSocialProtocol.string(message,"kind")))for(var m:stored.values())if(SaoSocialProtocol.number(m,"replyTo")==id&&m.has("quote")){var q=m.getAsJsonObject("quote");q.addProperty("text",message.get("text").getAsString());}
    }
    /// Keeps friend location to the two supported game labels, including responses from older floor-aware servers.
    static String location(SaoData.Presence presence,String where){return presence==SaoData.Presence.GAME?("单人游戏".equals(where)?"单人游戏":"誓约"):"";}
    /// Decodes server snapshots off JavaFX and publishes only immutable record values on JavaFX.
    private void apply(Session s,JsonObject state){
        for(var e:state.getAsJsonArray("chats")){var c=e.getAsJsonObject();String id=c.get("id").getAsString();long version=SaoSocialProtocol.number(c,"viewVersion");@Nullable Long prior=visibility.put(id,version);if(prior!=null&&prior!=version){messages.remove(id);older.remove(id);if(views.containsKey(id))loadOlder(id);}}
        var fs=new ArrayList<SaoData.Friend>();for(var e:state.getAsJsonArray("friends")){var f=e.getAsJsonObject();fs.add(new SaoData.Friend(f.get("name").getAsString(),SaoData.Presence.valueOf(f.get("presence").getAsString()),location(SaoData.Presence.valueOf(f.get("presence").getAsString()),f.get("where").getAsString()),null,SaoSocialProtocol.string(f,"id"),SaoSocialProtocol.string(f,"remark")));}fs.sort(Comparator.comparing(SaoData.Friend::presence).thenComparing(SaoData.Friend::name));var rs=new ArrayList<SaoData.FriendRequest>();for(var e:state.getAsJsonArray("requests")){var r=e.getAsJsonObject();rs.add(new SaoData.FriendRequest(r.get("name").getAsString(),r.get("incoming").getAsBoolean()));}var cs=new ArrayList<SaoData.Chat>();for(var e:state.getAsJsonArray("chats")){var c=e.getAsJsonObject();var members=new ArrayList<String>();c.getAsJsonArray("members").forEach(m->members.add(m.getAsString()));cs.add(new SaoData.Chat(c.get("id").getAsString(),c.get("title").getAsString(),c.get("group").getAsBoolean(),members,c.get("unread").getAsInt(),c.get("lastText").getAsString(),c.get("lastTime").getAsLong(),(int)SaoSocialProtocol.number(c,"mentions"),SaoSocialProtocol.flag(c,"muted"),SaoSocialProtocol.flag(c,"pinned"),SaoSocialProtocol.flag(c,"notify"),SaoSocialProtocol.number(c,"peerRead"),typingValues(c)));}
        var present=new HashSet<String>();cs.forEach(c->present.add(c.id()));messages.keySet().removeIf(id->!present.contains(id));older.keySet().removeIf(id->!present.contains(id));
        var snapshots=new HashMap<String,List<SaoData.ChatMessage>>();for(String id:views.keySet())snapshots.put(id,snapshot(s,id));
        publish(()->{if(session!=s)return;if(!friends.equals(fs))friends.setAll(fs);if(!requests.equals(rs))requests.setAll(rs);if(!chats.equals(cs))chats.setAll(cs);for(var e:snapshots.entrySet())if(!views.get(e.getKey()).equals(e.getValue()))views.get(e.getKey()).setAll(e.getValue());});
    }
    /// Copies transient typing values; the UI must also hide them after their lease expires.
    private static @Unmodifiable List<SaoData.Typing> typingValues(JsonObject chat){var out=new ArrayList<SaoData.Typing>();if(chat.has("typing"))for(var e:chat.getAsJsonArray("typing")){var t=e.getAsJsonObject();out.add(new SaoData.Typing(SaoSocialProtocol.string(t,"id"),SaoSocialProtocol.string(t,"from"),SaoSocialProtocol.number(t,"expiresAt")));}return List.copyOf(out);}
    /// Copies one ordered conversation to records; pending messages follow confirmed history.
    private List<SaoData.ChatMessage> snapshot(Session s,String id){var result=new ArrayList<SaoData.ChatMessage>();for(var m:messages.getOrDefault(id,new TreeMap<>()).values())result.add(SaoSocialProtocol.message(m));for(var p:pending.values())if(p.get("chat").getAsString().equals(id))result.add(new SaoData.ChatMessage(s.name(),p.get("text").getAsString(),p.get("time").getAsLong(),true,true));return result;}
    /// Queues an immutable message snapshot before crossing the JavaFX boundary.
    private void publishMessages(Session s,String id){List<SaoData.ChatMessage> values=snapshot(s,id);publish(()->{if(session==s){var view=views.computeIfAbsent(id,k->FXCollections.observableArrayList());if(!view.equals(values))view.setAll(values);}});}
    /// Adds/removes only pending rows from JavaFX, without reading worker-owned message maps.
    private void rebuildView(Session s,String id){var view=views.computeIfAbsent(id,k->FXCollections.observableArrayList());view.removeIf(SaoData.ChatMessage::pending);for(var p:pending.values())if(p.get("chat").getAsString().equals(id))view.add(new SaoData.ChatMessage(s.name(),p.get("text").getAsString(),p.get("time").getAsLong(),true,true));}
    /// Executes checked work under an account-generation fence; all futures complete on JavaFX.
    private CompletableFuture<JsonObject> submit(Action action){var result=new CompletableFuture<JsonObject>();futures.add(result);result.whenComplete((v,e)->futures.remove(result));@Nullable Session expected=session;long epoch=generation.get();
        if(expected==null||closed.get()){publish(()->result.completeExceptionally(new SaoAccountException("请先登录 ARGUS ID","signedOut")));return result;}
        try{worker.execute(()->{try{if(!valid(expected,epoch))throw new SaoAccountException("账号操作已取消","cancelled");JsonObject value=action.run(expected);publish(()->{if(valid(expected,epoch))result.complete(value);else result.completeExceptionally(new SaoAccountException("账号操作已取消","cancelled"));});}catch(Exception failure){SaoAccountException mapped=failure instanceof SaoAccountException known?known:new SaoAccountException("聊天同步失败，稍后再试","socialFailure");publish(()->result.completeExceptionally(mapped));}});}catch(RejectedExecutionException ended){publish(()->result.completeExceptionally(new SaoAccountException("聊天服务已关闭","closed")));}return result;
    }
    /// Tests origin and account generation before any queued request or publication can apply.
    private boolean valid(Session s,long epoch){return !closed.get()&&session==s&&generation.get()==epoch&&s.root().equals(client.root());}
    /// Runs lifecycle cleanup without accepting new operations after executor shutdown.
    private void execute(Runnable action){try{worker.execute(action);}catch(RejectedExecutionException ignored){}}
    /// Reads a small nonsecret per-origin/profile cursor; malformed state replays safely by message ID.
    private long readCursor(Session s){try{Path file=cursorFile(s);return Files.size(file)<64?Math.max(0,Long.parseLong(Files.readString(file).strip())):0;}catch(IOException|RuntimeException unavailable){return 0;}}
    /// Atomically persists only a delivery watermark; chat contents remain on the account server.
    private void writeCursor(Session s,long value)throws IOException{Path file=cursorFile(s);Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"cursor-",".tmp");try{Files.writeString(temp,Long.toString(value));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    /// Origin namespaces prevent another account server's UUID/cursor from sharing delivery state.
    private Path cursorFile(Session s){String host=UUID.nameUUIDFromBytes(s.root().toString().getBytes(StandardCharsets.UTF_8)).toString();return root.resolve("social-cursors").resolve(host).resolve(s.id()+".txt");}
    /// Closes a streaming body to interrupt blocking reads without affecting an independent device.
    private void closeInput(){@Nullable InputStream stream=input.getAndSet(null);if(stream!=null)try{stream.close();}catch(IOException ignored){}}
    /// Publishes on JavaFX in production; tests explicitly use the no-toolkit headless fallback.
    private static void publish(Runnable action){try{Platform.runLater(action);}catch(IllegalStateException headless){action.run();}}
    /// Creates simple JSON mutations without reflection or account-object serialization.
    static JsonObject object(String... pairs){var out=new JsonObject();for(int i=0;i<pairs.length;i+=2)out.addProperty(pairs[i],pairs[i+1]);return out;}
    /// Creates a JavaFX-completed local rejection for signed-out actions.
    private static <T> CompletableFuture<T> failed(String message,String code){var result=new CompletableFuture<T>();publish(()->result.completeExceptionally(new SaoAccountException(message,code)));return result;}
    /// Unwraps only fixed Chinese exception messages for void operations.
    private static String publicMessage(Throwable failure){while(failure instanceof CompletionException&&failure.getCause()!=null)failure=failure.getCause();return failure instanceof SaoAccountException?failure.getMessage():"聊天同步失败，稍后再试";}
    /// Releases both workers and live presence; it never revokes a different device's account session.
    @Override public void close(){if(!closed.compareAndSet(false,true))return;@Nullable Session old=session;session=null;generation.incrementAndGet();closeInput();live.shutdownNow();worker.shutdownNow();publish(()->futures.forEach(f->f.completeExceptionally(new SaoAccountException("聊天服务已关闭","closed"))));if(old!=null)CompletableFuture.runAsync(()->{try{client.jsonAt(old.root(),"POST","sao/social/disconnect",object("device",device),old.token());}catch(SaoAccountException ignored){}});}
}
