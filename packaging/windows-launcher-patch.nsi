; SPDX-License-Identifier: GPL-3.0-or-later
; Replaces only the bootstrap's bundled launcher JAR. The embedded installer CRC is mandatory.
Unicode true
ManifestDPIAware true
RequestExecutionLevel user
CRCCheck force
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "WordFunc.nsh"

!define APP_EXE "NerveGear启动器.exe"
!define FALLBACK_EXE "NerveGear-Launcher.exe"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\NerveGear-Launcher"
!define MUI_ICON "${ICON}"

Name "NerveGear启动器 ${VERSION} 补丁"
OutFile "${OUTFILE}"
InstallDir "$EXEDIR\"
BrandingText "NerveGear ${VERSION}"
AutoCloseWindow true
ShowInstDetails nevershow

Var TargetExe
Var Stage
Var Backup
Var Mutex

!define MUI_DIRECTORYPAGE_TEXT_TOP "请选择已有 NerveGear 正式版的文件夹，也就是包含 NerveGear启动器.exe 的那一层。"
!define MUI_PAGE_CUSTOMFUNCTION_PRE DirectoryPre
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE DirectoryLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

VIProductVersion "${VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductName" "NerveGear启动器"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileDescription" "NerveGear启动器 ${VERSION} 补丁"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "LegalCopyright" "GPL-3.0-or-later"

; Accept only the stable native layout and the matching bootstrap config for that EXE.
Function IdentifyTarget
  StrCpy $TargetExe ""
  StrCmp $INSTDIR "" done
  IfFileExists "$INSTDIR\app\nervegear-beta.marker" done
  IfFileExists "$INSTDIR\runtime\bin\javaw.exe" 0 done
  System::Call 'kernel32::GetFileAttributesW(w "$INSTDIR\app\launcher.jar") i.r0'
  StrCmp $0 -1 done
  IntOp $0 $0 & 0x410
  StrCmp $0 0 0 done
  StrCpy $1 "NerveGear启动器"
  IfFileExists "$INSTDIR\${APP_EXE}" read_cfg
  StrCpy $1 "NerveGear-Launcher"
  IfFileExists "$INSTDIR\${FALLBACK_EXE}" 0 done
  read_cfg:
  ReadINIStr $0 "$INSTDIR\app\$1.cfg" "Application" "app.mainclass"
  StrCmpS $0 "org.jackhuang.hmcl.sao.SaoLauncherBootstrap" 0 done
  ReadINIStr $0 "$INSTDIR\app\$1.cfg" "Application" "app.classpath"
  StrCmpS $0 "$$APPDIR/launcher.jar" version
  StrCmpS $0 "$$APPDIR\launcher.jar" 0 done
  version:
  ReadINIStr $0 "$INSTDIR\app\$1.cfg" "Application" "app.version"
  ${VersionCompare} "$0" "${VERSION}" $2
  StrCmp $2 1 done
  StrCpy $TargetExe "$1.exe"
  done:
FunctionEnd

Function .onInit
  SetShellVarContext current
  System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Local\NerveGearLauncherPatch") p.r0 ?e'
  Pop $1
  StrCpy $Mutex $0
  StrCmp $0 0 unavailable
  StrCmp $1 183 unavailable
  ; A colocated portable copy takes priority; otherwise use the installed copy.
  StrCpy $INSTDIR "$EXEDIR"
  Call IdentifyTarget
  StrCmp $TargetExe "" 0 selected
  ReadRegStr $INSTDIR HKCU "${UNINSTALL_KEY}" "InstallLocation"
  Call IdentifyTarget
  StrCmp $TargetExe "" 0 selected
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\NerveGear-Launcher"
  Call IdentifyTarget
  StrCmp $TargetExe "" 0 selected
  StrCpy $INSTDIR "$EXEDIR"
  selected:
  Return
  unavailable:
  MessageBox MB_OK|MB_ICONEXCLAMATION "已有补丁程序正在运行，请关闭后重试。" /SD IDOK
  SetErrorLevel 1
  Abort
FunctionEnd

Function DirectoryPre
  StrCmp $TargetExe "" +2
  Abort
FunctionEnd

Function DirectoryLeave
  Call IdentifyTarget
  StrCmp $TargetExe "" 0 done
  MessageBox MB_OK|MB_ICONEXCLAMATION "请选择现有的 NerveGear 正式版目录。此补丁适用于 ${VERSION} 或更旧的原生启动器。" /SD IDOK
  Abort
  done:
FunctionEnd

; Native process snapshot, with no PowerShell or WMI dependency. Only processes that can
; belong to this launcher are queried for their image path. The patch terminates nothing.
Function WaitForLauncher
  retry:
  System::Call 'kernel32::CreateToolhelp32Snapshot(i 2, i 0) p.r0'
  StrCmp $0 -1 query_failed
  System::Alloc 556
  Pop $1
  StrCmp $1 0 allocation_failed
  System::Call '*$1(i 556)'
  System::Call 'kernel32::Process32FirstW(p r0, p r1) i.r2 ?e'
  Pop $9
  loop:
  StrCmp $2 0 finished
  System::Call '*$1(i, i, i .r4, i, i, i, i, i, i, &w260 .r3)'
  StrCmp $3 "$TargetExe" inspect
  StrCmp $3 "java.exe" inspect
  StrCmp $3 "javaw.exe" 0 next_process
  inspect:
  System::Call 'kernel32::OpenProcess(i 0x1000, i 0, i r4) p.r5 ?e'
  Pop $9
  StrCmp $5 0 unreadable
  StrCpy $6 ${NSIS_MAX_STRLEN}
  System::Call 'kernel32::QueryFullProcessImageNameW(p r5, i 0, w .r7, *i r6) i.r8 ?e'
  Pop $9
  System::Call 'kernel32::CloseHandle(p r5)'
  StrCmp $8 0 unreadable
  StrCmp $7 "$INSTDIR\$TargetExe" busy
  StrCmp $7 "$INSTDIR\runtime\bin\java.exe" busy
  StrCmp $7 "$INSTDIR\runtime\bin\javaw.exe" busy next_process
  unreadable:
  ; A process may have exited between the snapshot and OpenProcess (87).
  StrCmp $9 87 next_process
  Goto busy
  next_process:
  System::Call 'kernel32::Process32NextW(p r0, p r1) i.r2 ?e'
  Pop $9
  Goto loop
  finished:
  System::Free $1
  System::Call 'kernel32::CloseHandle(p r0)'
  StrCmp $9 18 0 query_failed
  Return
  busy:
  System::Free $1
  System::Call 'kernel32::CloseHandle(p r0)'
  MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "请先关闭正在运行的 NerveGear，再点重试。" /SD IDCANCEL IDRETRY retry
  SetErrorLevel 1
  Abort
  allocation_failed:
  System::Call 'kernel32::CloseHandle(p r0)'
  query_failed:
  MessageBox MB_OK|MB_ICONSTOP "无法确认 NerveGear 已关闭，补丁未应用。" /SD IDOK
  SetErrorLevel 1
  Abort
FunctionEnd

Section "Patch"
  ; DirectoryLeave is skipped in silent mode; validate the same target there too.
  StrCmp $TargetExe "" 0 identified
  Call IdentifyTarget
  StrCmp $TargetExe "" invalid_target
  identified:
  Call WaitForLauncher
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  ClearErrors
  File /oname=launcher.jar "${PAYLOAD}"
  IfErrors patch_failed
  FileOpen $0 "$PLUGINSDIR\launcher.jar" r
  IfErrors patch_failed
  FileSeek $0 0 END $1
  FileClose $0
  StrCmp $1 "${PAYLOAD_BYTES}" 0 patch_failed

  ClearErrors
  GetTempFileName $Stage "$INSTDIR\app"
  IfErrors patch_failed
  System::Call 'kernel32::CopyFileW(w "$PLUGINSDIR\launcher.jar", w "$Stage", i 0) i.r0'
  StrCmp $0 0 patch_failed

  StrCpy $Backup "$INSTDIR\app\launcher.before-${VERSION}.jar.bak"
  StrCpy $0 1
  backup_name:
  IfFileExists "$Backup" 0 make_backup
  IntOp $0 $0 + 1
  StrCpy $Backup "$INSTDIR\app\launcher.before-${VERSION}.$0.jar.bak"
  Goto backup_name
  make_backup:
  System::Call 'kernel32::CopyFileW(w "$INSTDIR\app\launcher.jar", w "$Backup", i 1) i.r0'
  StrCmp $0 0 patch_failed
  ; The stage and destination share a directory. No cross-volume copy is permitted.
  ; MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH commits without a rename-away gap.
  System::Call 'kernel32::MoveFileExW(w "$Stage", w "$INSTDIR\app\launcher.jar", i 9) i.r0'
  StrCmp $0 0 patch_failed
  MessageBox MB_OK|MB_ICONINFORMATION "已更新到 ${VERSION}。请重新打开 NerveGear 启动器。" /SD IDOK
  Goto done

  invalid_target:
  MessageBox MB_OK|MB_ICONSTOP "找不到现有的 NerveGear 正式版，补丁未应用。" /SD IDOK
  SetErrorLevel 2
  Abort
  patch_failed:
  StrCmp $Stage "" +2
  Delete "$Stage"
  MessageBox MB_OK|MB_ICONSTOP "补丁未应用。请关闭 NerveGear 并确认启动器文件夹可写，再重试。" /SD IDOK
  SetErrorLevel 3
  Abort
  done:
SectionEnd
