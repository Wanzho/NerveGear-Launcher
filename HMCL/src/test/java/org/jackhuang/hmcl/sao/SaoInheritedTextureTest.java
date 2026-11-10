package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/// Headless source-state contracts against isolated loopback HTTP fixtures; no Microsoft or public requests.
@NotNullByDefault
class SaoInheritedTextureTest {
    /// Entire disposable account/cache root used by the serialized controller.
    @TempDir Path temporary;
    /// Disposable profile identity used by local response bodies only.
    private static final String PROFILE="1234567890abcdef1234567890abcdef";
    /// Local fixture hash checked against every preview URI before publication.
    private static final String HASH="a".repeat(64);
    /// Local account/provider state without a game server, toolkit, browser or real credentials.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Only literal-loopback server socket used by this fixture.
        final HttpServer server;
        /// Current synthetic source selection, mirroring successful choice writes.
        final AtomicReference<String> skin=new AtomicReference<>("premium");
        /// Current synthetic cape selection, including none.
        final AtomicReference<String> cape=new AtomicReference<>("mojang");
        /// Initial linked idle responses before a queued initialization worker starts fetching.
        final AtomicInteger initialIdle=new AtomicInteger();
        /// Number of remaining syncing responses before background work completes.
        final AtomicInteger syncing=new AtomicInteger();
        /// Stable synthetic refresh timestamp.
        final AtomicLong refreshed=new AtomicLong(1800000000000L);
        /// Fixture business rejection code; arbitrary server diagnostics never reach labels.
        volatile String failure="";
        /// Malicious preview URL used only by the origin-validation contract.
        volatile @Nullable String imageUrl;
        /// Last bearer received, used to prove captured-origin credentials never reach another server.
        final AtomicReference<String> bearer=new AtomicReference<>("");
        /// Authenticated preview-state reads performed by the initial polling path.
        final AtomicInteger reads=new AtomicInteger();
        /// Opens an ephemeral local HTTP API fixture.
        Fixture()throws IOException {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::respond);server.start();}
        /// Returns the explicit loopback origin without DNS.
        URI root(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
        /// Builds bounded state with a same-origin content-addressed preview.
        JsonObject state(){
            var image=new JsonObject();image.addProperty("hash",HASH);image.addProperty("model","slim");image.addProperty("url",imageUrl==null?root().resolve("textures/"+HASH).toString():imageUrl);
            boolean idle=initialIdle.getAndUpdate(n->Math.max(0,n-1))>0;var images=new JsonObject();images.add("premiumSkin",image);var value=new JsonObject();value.addProperty("linked",true);value.addProperty("skinSource",skin.get());value.addProperty("capeSource",cape.get());value.addProperty("effectiveSkinSource",skin.get());value.addProperty("effectiveCapeSource",cape.get());value.add("images",images);value.addProperty("lastRefresh",idle?0:refreshed.get());value.addProperty("refreshAfter",180);value.addProperty("status",idle?"idle":syncing.getAndUpdate(n->Math.max(0,n-1))>0?"syncing":"synced");return value;
        }
        /// Routes only synthetic account/source responses and absorbs unrelated social fixture polls locally.
        void respond(HttpExchange exchange)throws IOException {
            @Nullable String auth=exchange.getRequestHeaders().getFirst("Authorization");if(auth!=null)bearer.set(auth);
            String path=exchange.getRequestURI().getPath();JsonObject value=new JsonObject();int status=200;
            if(path.startsWith("/sao/textures")){
                reads.incrementAndGet();
                if(!failure.isEmpty()){status=failure.equals("textureRefreshLimited")?429:403;value.addProperty("error",failure);value.addProperty("errorMessage","PrivateProviderDiagnostic");value.addProperty("waitSeconds",180);}
                else if(path.endsWith("/choices")){var input=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();if(input.has("skinSource"))skin.set(input.get("skinSource").getAsString());if(input.has("capeSource"))cape.set(input.get("capeSource").getAsString());value=state();}
                else if(path.endsWith("/refresh")){syncing.set(2);refreshed.incrementAndGet();value=state();status=202;}
                else value=state();
            }else if(path.equals("/sao/login")){value.addProperty("accessToken","FixtureBearer");value.addProperty("clientToken","FixtureClient");value.add("selectedProfile",JsonParser.parseString("{\"id\":\""+PROFILE+"\",\"name\":\"ArgusFixture\"}"));}
            else if(path.equals("/sao/account")){value=JsonParser.parseString("{\"id\":\""+PROFILE+"\",\"name\":\"ArgusFixture\",\"state\":\"ready\",\"emailVerified\":true,\"graceSeconds\":0,\"premiumName\":\"PremiumFixture\"}").getAsJsonObject();}
            else if(path.contains("/session/minecraft/profile/")){value.add("properties",new JsonArray());}
            else if(path.equals("/sao/card")){value=JsonParser.parseString("{\"level\":-1,\"col\":-1,\"floor\":-1,\"dimension\":\"\",\"friends\":[]}").getAsJsonObject();}
            else if(path.startsWith("/sao/social/")){status=503;value.addProperty("error","socialSession");value.addProperty("errorMessage","Unavailable");}
            byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }
        /// Closes the only network listener owned by this fixture.
        @Override public void close(){server.stop(0);}
    }
    /// Decodes immutable descriptors and writes both source choices under the captured bearer origin.
    @Test void stateAndChoicesUseAuthenticatedPinnedOrigin()throws Exception {
        try(var first=new Fixture();var second=new Fixture()){
            var configured=new AtomicReference<>(first.root());var client=new SaoAccountClient(configured::get,()->"zh_cn");URI captured=first.root();configured.set(second.root());
            var state=client.inheritedTextures(captured,"CapturedBearer");assertTrue(state.linked());assertEquals("slim",state.images().get("premiumSkin").model());assertEquals(captured.resolve("textures/"+HASH),state.images().get("premiumSkin").url());assertThrows(UnsupportedOperationException.class,()->state.images().clear());
            assertEquals("own",client.textureChoices(captured,"own",null,"CapturedBearer").skinSource());assertEquals("none",client.textureChoices(captured,null,"none","CapturedBearer").capeSource());
            assertEquals("Bearer CapturedBearer",first.bearer.get());assertEquals("",second.bearer.get());assertEquals(0,second.reads.get());
        }
    }
    /// Unknown origins and mismatched hashes never become image URLs passed to frontend loaders.
    @Test void remoteAndMismatchedPreviewsAreRefused()throws Exception {
        try(var fixture=new Fixture()){
            var client=new SaoAccountClient(fixture::root,()->"zh_cn");
            for(String url:List.of("https://unlisted.example/textures/"+HASH,fixture.root().resolve("textures/"+"b".repeat(64)).toString(),fixture.root().resolve("textures/"+HASH+"?secret=1").toString())){
                fixture.imageUrl=url;var failure=assertThrows(SaoAccountException.class,()->client.inheritedTextures(fixture.root(),"FixtureBearer"));assertTrue(Set.of("invalidTexture","invalidTextureState").contains(failure.code()));
            }
        }
    }
    /// Cooldown labels retain exact remaining minutes and never expose provider text.
    @Test void curatedCooldownAndUnlinkedLabels()throws Exception {
        try(var fixture=new Fixture()){
            var client=new SaoAccountClient(fixture::root,()->"zh_cn");fixture.failure="textureRefreshLimited";
            var limited=assertThrows(SaoAccountException.class,()->client.refreshInheritedTextures(fixture.root(),"FixtureBearer"));assertEquals("操作太频繁，请 3 分钟后再试",limited.getMessage());fixture.failure="premiumUnlinked";
            assertEquals("未绑定微软账号",assertThrows(SaoAccountException.class,()->client.refreshInheritedTextures(fixture.root(),"FixtureBearer")).getMessage());
        }
    }
    /// Initial linking refresh completes after sign-in through scheduled account-worker polling, and signout clears it.
    @Test void controllerPollsInitialAndManualRefreshWithoutGui()throws Exception {
        try(var fixture=new Fixture()){
            fixture.initialIdle.set(2);fixture.syncing.set(2);
            try(var accounts=new SaoOathAccounts(temporary,fixture::root,()->"zh_cn",new MemoryCredentials(),()->{throw new AssertionError("Microsoft must not open");},account->{throw new AssertionError("Game must not start");},message->{})){
                var statuses=new ConcurrentLinkedQueue<String>();accounts.inheritedTextures().addListener((observable,previous,current)->statuses.add(current.status()));
                accounts.login("ArgusFixture","FixturePassword42".toCharArray(),false).get(10,TimeUnit.SECONDS);
                assertNotNull(accounts.account().get());assertEquals("idle",accounts.inheritedTextures().get().status());assertEquals(0,accounts.inheritedTextures().get().lastRefresh());
                assertTimeoutPreemptively(Duration.ofSeconds(8),()->{while(!accounts.inheritedTextures().get().status().equals("synced"))Thread.sleep(20);});
                assertTrue(statuses.containsAll(List.of("idle","syncing","synced")));assertTrue(fixture.reads.get()>=5,"Queued initial idle state must continue polling until a terminal result");
                accounts.setSkinSource("own").get(10,TimeUnit.SECONDS);accounts.setCapeSource("none").get(10,TimeUnit.SECONDS);assertEquals("own",accounts.inheritedTextures().get().skinSource());assertEquals("none",accounts.inheritedTextures().get().capeSource());
                accounts.refreshInheritedTextures().get(10,TimeUnit.SECONDS);assertEquals("已同步",accounts.inheritedTextures().get().statusLabel());accounts.logout();assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(accounts.account().get()!=null)Thread.sleep(10);});assertFalse(accounts.inheritedTextures().get().linked());
            }
        }
    }
    /// Fake appearance previews are local valid PNGs with exact hashes and distinct source entries.
    @Test void sampleImagesMatchProductionSlotsAndDimensions()throws Exception {
        var images=SaoTextureSamples.images();assertEquals(6,images.size());for(var entry:images.entrySet()){
            var image=entry.getValue();assertEquals("file",image.url().getScheme());byte[] bytes=Files.readAllBytes(Path.of(image.url()));assertEquals(image.hash(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));var decoded=Objects.requireNonNull(ImageIO.read(new ByteArrayInputStream(bytes)));assertEquals(64,decoded.getWidth());assertEquals(entry.getKey().endsWith("Skin")?64:32,decoded.getHeight());
        }
        assertEquals("未绑定微软账号",SaoTextureState.empty().statusLabel());
    }
    /// In-memory credential fixture owns no native keychain, persisted account or password copy.
    @NotNullByDefault
    private static final class MemoryCredentials implements SaoOathCredentials {
        /// No credential is remembered by these tests.
        @Override public @Nullable Credential read(){return null;}
        /// Remembering is disabled for the local test controller.
        @Override public void write(String name,char[] password){}
        /// No durable credential exists to delete.
        @Override public void delete(){}
        /// Identifies the isolated fixture backend without user data.
        @Override public String backend(){return "fixture";}
    }
}
