; NerveGear启动器 Windows installer (James, 12 Nov 2026: "能做个更方便的东西吗？").
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
ShowInstDetails nevershow
ShowUninstDetails nevershow

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING

!define MUI_LICENSEPAGE_TEXT_TOP "GPL-3.0 开源许可"
!define MUI_LICENSEPAGE_TEXT_BOTTOM " "
!define MUI_LICENSEPAGE_BUTTON "$(^NextBtn)"
!insertmacro MUI_PAGE_LICENSE "${PAYLOAD}\licenses\LICENSE-launcher-GPL-3.0.txt"
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE DirectoryLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "启动 ${APP_NAME}"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "桌面快捷方式"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION DesktopShortcut
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

; A running launcher holds files under runtime\ open (jvm.dll, javaw.exe), so the folder cannot be renamed.
!macro RUNNING_CHECK PREFIX
Function ${PREFIX}WaitForLauncher
  retry:
  IfFileExists "$INSTDIR\runtime\*.*" 0 done
  ClearErrors
  Rename "$INSTDIR\runtime" "$INSTDIR\runtime.nervegear-check"
  IfErrors busy
  Rename "$INSTDIR\runtime.nervegear-check" "$INSTDIR\runtime"
  Goto done
  busy:
  MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "${APP_NAME}正在运行，先关掉它。" /SD IDCANCEL IDRETRY retry
  Abort
  done:
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

Function DirectoryLeave
  Call CheckWritable
  Call WaitForLauncher
FunctionEnd

Function DesktopShortcut
  SetOutPath "$INSTDIR"
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
FunctionEnd

Section "Install"
  Call CheckWritable
  Call WaitForLauncher
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
