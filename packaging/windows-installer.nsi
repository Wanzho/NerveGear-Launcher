; NerveGear启动器 one-click, per-user Windows installer.
; Per-user, no admin. Installs the exact portable folder (exe, app, runtime, licenses, README-zh.txt); the launcher keeps
; its data in .sao-oath-launcher beside itself (D-1), which install, upgrade and the default uninstall never touch.
; Built by packaging/package.py with: -DPAYLOAD=<NerveGear-Launcher folder> -DICON=<launcher.ico> -DOUTFILE=<exe>
; -DVERSION=0.1.0 -DSIZE_KB=<payload size>. SPDX-License-Identifier: GPL-3.0-or-later

Unicode true
ManifestDPIAware true
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"

!define APP_NAME "NerveGear启动器"
!define APP_EXE "NerveGear启动器.exe"
!define PUBLISHER "刀剑神域·誓约"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\NerveGear-Launcher"
!define UNINSTALLER "uninstall.exe"

Name "${APP_NAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\NerveGear-Launcher"
InstallDirRegKey HKCU "${UNINSTALL_KEY}" "InstallLocation"
BrandingText "${APP_NAME} ${VERSION}"
AutoCloseWindow true
ShowInstDetails nevershow
ShowUninstDetails nevershow

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_INSTFILES
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
  System::Call '*$1(i, i, i, i, i, i, i, i, i, &w260 .r3)'
  StrCmp $3 "${APP_EXE}" busy
  System::Call 'kernel32::Process32NextW(p r0, p r1) i.r2'
  Goto loop
  busy:
  System::Free $1
  System::Call 'kernel32::CloseHandle(p r0)'
  MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "请先关闭 NerveGear启动器 再安装" /SD IDCANCEL IDRETRY retry
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

; The launcher writes .sao-oath-launcher here, so a folder the player cannot write to (Program Files) is refused.
Function CheckWritable
  ClearErrors
  CreateDirectory "$INSTDIR"
  FileOpen $0 "$INSTDIR\.nervegear-write-test" w
  ${If} ${Errors}
    MessageBox MB_OK|MB_ICONEXCLAMATION "这个文件夹不能写入，换一个。" /SD IDOK
    Abort
  ${EndIf}
  FileClose $0
  Delete "$INSTDIR\.nervegear-write-test"
FunctionEnd

Function .onInit
  SetShellVarContext current
  Call WaitForLauncher
FunctionEnd

; Only a successful installation launches the app; user execution level is inherited.
Function .onInstSuccess
  SetOutPath "$INSTDIR"
  Exec '"$INSTDIR\${APP_EXE}"'
FunctionEnd

Section "Install"
  Call WaitForLauncher
  Call CheckWritable
  ; Upgrade: replace the program, never .sao-oath-launcher.
  ${If} ${FileExists} "$INSTDIR\app\launcher.jar"
    RMDir /r "$INSTDIR\app"
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\licenses"
    Delete "$INSTDIR\${APP_EXE}"
    Delete "$INSTDIR\README-zh.txt"
  ${EndIf}
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD}\*.*"
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
    RMDir /r "$INSTDIR\.sao-oath-launcher"
    RMDir /r "$APPDATA\.sao-oath-launcher"
  ${EndIf}
  Delete "$INSTDIR\${UNINSTALLER}"
  RMDir "$INSTDIR"
SectionEnd
