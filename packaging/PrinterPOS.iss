#ifndef AppSource
  #error Define AppSource with the portable application directory
#endif
#ifndef SetupOutput
  #define SetupOutput "..\portable\setup"
#endif
[Setup]
AppId={{E501A1E5-CC99-4B55-AE93-0F94BCD39068}
AppName=PrinterPOS
AppVersion=1.2.0
AppPublisher=PrinterPOS
DefaultDirName={localappdata}\Programs\PrinterPOS
DefaultGroupName=PrinterPOS
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#SetupOutput}
OutputBaseFilename=PrinterPOS-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\TicketPrinterServerUI.exe
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"; Flags: unchecked

[Files]
Source: "{#AppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\PrinterPOS"; Filename: "{app}\TicketPrinterServerUI.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\PrinterPOS"; Filename: "{app}\TicketPrinterServerUI.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\TicketPrinterServerUI.exe"; Description: "Abrir PrinterPOS"; Flags: nowait postinstall skipifsilent
