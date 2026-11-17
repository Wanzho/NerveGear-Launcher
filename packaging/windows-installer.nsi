; NerveGear启动器 one-click, per-user Windows installer.
; Per-user, no admin. Installs the exact portable folder (exe, app, runtime, licenses, README-zh.txt); the launcher keeps
; its data in .sao-oath-launcher beside itself (D-1), which install, upgrade and the default uninstall never touch.
; Built by packaging/package.py with: -DPAYLOAD=<NerveGear-Launcher folder> -DICON=<launcher.ico> -DOUTFILE=<exe>
; -DVERSION=0.1.3 -DSIZE_KB=<payload size>. SPDX-License-Identifier: GPL-3.0-or-later

Unicode true
ManifestDPIAware true
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"

!ifdef SAO_BETA
!define APP_NAME "NerveGear启动器 Beta"
!define APP_FOLDER "NerveGear-Launcher-Beta"
!define DATA_FOLDER ".sao-oath-launcher-beta"
!else
!define APP_NAME "NerveGear启动器"
!define APP_FOLDER "NerveGear-Launcher"
!define DATA_FOLDER ".sao-oath-launcher"
!endif
!define APP_EXE "${APP_NAME}.exe"
!define PUBLISHER "刀剑神域·誓约"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_FOLDER}"
!define UNINSTALLER "uninstall.exe"

Name "${APP_NAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APP_FOLDER}"
InstallDirRegKey HKCU "${UNINSTALL_KEY}" "InstallLocation"
BrandingText "${APP_NAME} ${VERSION}"
AutoCloseWindow true
ShowInstDetails nevershow
ShowUninstDetails nevershow

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING

Var IsUpgrade
Var FinishTitle
Var FinishText
Var VolumePath
Var NewFiles

; Fresh installs choose both program and initial game-data location. Upgrades retain the registered location.
!define MUI_DIRECTORYPAGE_TEXT_TOP "启动器、游戏、整合包和存档都放在这个文件夹内，约需 5 GB。C 盘空间小，请选择其他盘的文件夹，例如 D:\Games\NerveGear。以后可在设置 → 游戏位置搬家。"
!define MUI_PAGE_CUSTOMFUNCTION_PRE DirectoryPre
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE CheckWritable
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "$FinishTitle"
!define MUI_FINISHPAGE_TEXT "$FinishText"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "启动 ${APP_NAME}"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "CompanyName" "${PUBLISHER}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileDescription" "${APP_NAME} 安装"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=${LANG_SIMPCHINESE} "LegalCopyright" "GPL-3.0-or-later"

; Enumerate processes without terminating them or renaming a live runtime.
; NSIS is 32-bit: PROCESSENTRY32W is 556 bytes, with szExeFile at byte 36.
!macro RUNNING_CHECK PREFIX
Function ${PREFIX}WaitForLauncher
  retry:
  System::Call 'kernel32::CreateToolhelp32Snapshot(i 2, i 0) p.r0'
  StrCmp $0 -1 failed
  System::Alloc 556
  Pop $1
  StrCmp $1 0 allocation_failed
  System::Call '*$1(i 556)'
  System::Call 'kernel32::Process32FirstW(p r0, p r1) i.r2'
  loop:
  StrCmp $2 0 finished
  System::Call '*$1(i, i, i .r4, i, i, i, i, i, i, &w260 .r3)'
  StrCmp $3 "${APP_EXE}" busy
  StrCmp $3 "java.exe" inspect_java
  StrCmp $3 "javaw.exe" inspect_java next_process
  inspect_java:
  System::Call 'kernel32::OpenProcess(i 0x1000, i 0, i r4) p.r5'
  StrCmp $5 0 busy
  StrCpy $6 ${NSIS_MAX_STRLEN}
  System::Call 'kernel32::QueryFullProcessImageNameW(p r5, i 0, w .r7, *i r6) i.r8'
  System::Call 'kernel32::CloseHandle(p r5)'
  StrCmp $8 0 busy
  StrLen $6 "$INSTDIR\"
  StrCpy $7 $7 $6
  StrCmp $7 "$INSTDIR\" busy
  next_process:
  System::Call 'kernel32::Process32NextW(p r0, p r1) i.r2'
  Goto loop
  busy:
  System::Free $1
  System::Call 'kernel32::CloseHandle(p r0)'
  MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "请先关闭启动器和游戏再更新" /SD IDCANCEL IDRETRY retry
  Abort
  finished:
  System::Free $1
  System::Call 'kernel32::CloseHandle(p r0)'
  Return
  allocation_failed:
  System::Call 'kernel32::CloseHandle(p r0)'
  failed:
  MessageBox MB_OK|MB_ICONSTOP "无法检查启动器是否正在运行，请重试。" /SD IDOK
  Abort
FunctionEnd
!macroend
!insertmacro RUNNING_CHECK ""
!insertmacro RUNNING_CHECK "un."

; The directory page is skipped only when the registered executable proves this is an upgrade.
Function DirectoryPre
  StrCmp $IsUpgrade 1 0 done
  Abort
  done:
FunctionEnd

; Actual NTFS/free-space/write checks; no reliance on the small program payload size.
Function CheckWritable
!ifdef SAO_BETA
  ; Protect the stable edition and any foreign app payload, including custom paths and silent installs.
  IfFileExists "$INSTDIR\NerveGear启动器.exe" occupied
  IfFileExists "$INSTDIR\.sao-oath-launcher\*.*" occupied
  IfFileExists "$INSTDIR\app\nervegear-beta.marker" edition_ok
  IfFileExists "$INSTDIR\app\*.*" occupied
  IfFileExists "$INSTDIR\runtime\*.*" occupied
  IfFileExists "$INSTDIR\uninstall.exe" occupied
  Goto edition_ok
  occupied:
  MessageBox MB_OK|MB_ICONSTOP "这里已有正式版或其他程序，请为 Beta 选择独立文件夹。" /SD IDOK
  Abort
  edition_ok:
!endif
  ClearErrors
  CreateDirectory "$INSTDIR"
  FileOpen $0 "$INSTDIR\.nervegear-write-test" w
  ${If} ${Errors}
    MessageBox MB_OK|MB_ICONEXCLAMATION "这个文件夹不能写入，换一个。" /SD IDOK
    Abort
  ${EndIf}
  FileClose $0
  Delete "$INSTDIR\.nervegear-write-test"
  StrCmp $IsUpgrade 1 done
  System::Call 'kernel32::GetVolumePathNameW(w "$INSTDIR", w .r1, i ${NSIS_MAX_STRLEN}) i.r0'
  StrCmp $0 0 probe_failed
  StrCpy $VolumePath $1
  System::Call 'kernel32::GetVolumeInformationW(w r1, p 0, i 0, p 0, p 0, p 0, w .r2, i ${NSIS_MAX_STRLEN}) i.r0'
  StrCmp $0 0 probe_failed
  StrCmp $2 "NTFS" check_space
  MessageBox MB_OK|MB_ICONEXCLAMATION "这个盘是 $2 格式，不能装在这里（需要 NTFS）。" /SD IDOK
  Abort
  check_space:
  System::Call 'kernel32::GetDiskFreeSpaceExW(w "$VolumePath", *l .r3, p 0, p 0) i.r0'
  StrCmp $0 0 probe_failed
  System::Int64Op $3 >= 5368709120
  Pop $0
  StrCmp $0 1 done
  System::Int64Op 5368709120 - $3
  Pop $3
  System::Int64Op $3 + 1073741823
  Pop $3
  System::Int64Op $3 / 1073741824
  Pop $3
  StrCpy $1 $VolumePath 1
  MessageBox MB_OK|MB_ICONEXCLAMATION "$1 盘空间不够，还需要 $3 GB" /SD IDOK
  Abort
  probe_failed:
  MessageBox MB_OK|MB_ICONSTOP "无法检查磁盘格式或剩余空间，请换一个本地文件夹。" /SD IDOK
  Abort
  done:
FunctionEnd

Function .onInit
  SetShellVarContext current
  StrCpy $IsUpgrade 0
  StrCpy $FinishTitle "已安装 ${VERSION}"
  StrCpy $FinishText "启动器已安装。游戏、整合包和存档位于所选文件夹。"
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
  StrCmp $0 "" fresh
  IfFileExists "$0\${APP_EXE}" 0 fresh
  StrCpy $INSTDIR $0
  StrCpy $IsUpgrade 1
  StrCpy $FinishTitle "已更新到 ${VERSION}"
  StrCpy $FinishText "更新会装在原来的位置。游戏和存档保持不变；搬家请使用设置 → 游戏位置。"
  fresh:
  Call WaitForLauncher
FunctionEnd

Section "Install"
  Call WaitForLauncher
  Call CheckWritable
  ; Include the game/pack/Java budget in the directory page's required-space display.
  AddSize 5242880
  ; Extract completely before touching the installed app. A previous interrupted update requires recovery first.
  IfFileExists "$INSTDIR\.nervegear-update-old\*.*" recovery_needed
  IfFileExists "$INSTDIR\.nervegear-update-new\*.*" recovery_needed
  ClearErrors
  SetOutPath "$INSTDIR\.nervegear-update-new"
  File /r "${PAYLOAD}\*.*"
  IfErrors extraction_failed
  Call WaitForLauncher
  SetOutPath "$INSTDIR"
  CreateDirectory "$INSTDIR\.nervegear-update-old"
  StrCpy $NewFiles 0
  ; Whole-directory rename prevents partially replacing app/. Every rename is checked.
!macro BACKUP NAME
  IfFileExists "$INSTDIR\${NAME}" 0 +4
  ClearErrors
  Rename "$INSTDIR\${NAME}" "$INSTDIR\.nervegear-update-old\${NAME}"
  IfErrors rollback
!macroend
  !insertmacro BACKUP "app"
  !insertmacro BACKUP "runtime"
  !insertmacro BACKUP "licenses"
  !insertmacro BACKUP "${APP_EXE}"
  !insertmacro BACKUP "README-zh.txt"
  StrCpy $NewFiles 1
!macro ACTIVATE NAME
  ClearErrors
  Rename "$INSTDIR\.nervegear-update-new\${NAME}" "$INSTDIR\${NAME}"
  IfErrors rollback
!macroend
  !insertmacro ACTIVATE "app"
  !insertmacro ACTIVATE "runtime"
  !insertmacro ACTIVATE "licenses"
  !insertmacro ACTIVATE "${APP_EXE}"
  !insertmacro ACTIVATE "README-zh.txt"
  RMDir /r "$INSTDIR\.nervegear-update-old"
  RMDir /r "$INSTDIR\.nervegear-update-new"
  Goto installed
  rollback:
  ; If activation began, remove only newly activated entries; every original remains in the backup.
  StrCmp $NewFiles 1 0 restore
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\README-zh.txt"
  restore:
!macro RESTORE NAME
  IfFileExists "$INSTDIR\.nervegear-update-old\${NAME}" 0 +2
  Rename "$INSTDIR\.nervegear-update-old\${NAME}" "$INSTDIR\${NAME}"
!macroend
  !insertmacro RESTORE "app"
  !insertmacro RESTORE "runtime"
  !insertmacro RESTORE "licenses"
  !insertmacro RESTORE "${APP_EXE}"
  !insertmacro RESTORE "README-zh.txt"
  RMDir "$INSTDIR\.nervegear-update-old"
  RMDir /r "$INSTDIR\.nervegear-update-new"
  MessageBox MB_OK|MB_ICONSTOP "无法更新文件，已尝试恢复原版本。请关闭启动器和游戏后重试；若仍失败，请保留 .nervegear-update-old 文件夹。" /SD IDOK
  Abort
  extraction_failed:
  SetOutPath "$INSTDIR"
  RMDir /r "$INSTDIR\.nervegear-update-new"
  MessageBox MB_OK|MB_ICONSTOP "无法解压更新，原版本未修改。请检查剩余空间后重试。" /SD IDOK
  Abort
  recovery_needed:
  MessageBox MB_OK|MB_ICONSTOP "发现未完成的更新，请先恢复 .nervegear-update-old 中的原版本。游戏和存档未修改。" /SD IDOK
  Abort
  installed:
  WriteUninstaller "$INSTDIR\${UNINSTALLER}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${APP_EXE},0"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\${UNINSTALLER}"'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\${UNINSTALLER}" /S'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "EstimatedSize" ${SIZE_KB}
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Function un.onInit
  SetShellVarContext current
  Call un.WaitForLauncher
FunctionEnd

; Also run silently (/S) by the launcher's own 一键卸载 after it removed the program and data itself.
Section "Uninstall"
  StrCpy $1 0
  MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 "同时删除游戏和存档？" /SD IDNO IDNO keep
  StrCpy $1 1
  keep:
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\README-zh.txt"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\licenses"
  ; Shortcuts and the entry belong to this folder only when the entry still names it (a later install elsewhere wins).
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
  ${If} $0 == $INSTDIR
    Delete "$SMPROGRAMS\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME}.lnk"
    DeleteRegKey HKCU "${UNINSTALL_KEY}"
  ${EndIf}
  ${If} $1 == 1
    RMDir /r "$INSTDIR\${DATA_FOLDER}"
    RMDir /r "$APPDATA\${DATA_FOLDER}"
  ${EndIf}
  Delete "$INSTDIR\${UNINSTALLER}"
  RMDir "$INSTDIR"
SectionEnd
