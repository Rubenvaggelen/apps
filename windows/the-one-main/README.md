# The One Window

Native Windows rebuild of The One Window.

## Included
- The One dashboard style and tile layout
- Chrome tile and Chrome-first web launching
- Auto-start with Windows sign-in
- Mail & Calendar
- Huishouden shopping list synchronized with The One Main and The One Car over the existing The One local-network protocol
- Ask (Groq key configurable locally)
- Recipes
- News
- Radio
- EUR / SRD / USD currency conversion
- Finance budget tracker
- Lifestyle / fitness log
- Windows app shortcuts
- Hide/restore tiles
- Local The One notification history

Android The One Window is not modified by this Windows project.

## Build
```powershell
dotnet publish .\TheOneMain.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

The executable is created under `bin\Release\net8.0-windows\win-x64\publish\TheOneMain.exe`.

## Updates
The Windows app has its own in-app update channel.
Windows builds are published as GitHub prereleases with tags `windows-v<build>`.
The app checks this channel on startup and can replace its own files after closing, then restart automatically.
These Windows-only releases are intentionally separate from the Android The One / The One Car release channel.


## Vaste Windows-installatie
Bij de eerste start kopieert The One Window zichzelf automatisch naar:
`%LOCALAPPDATA%\Programs\The One Family\The One Window\TheOneMain.exe`

Daarna maakt de app automatisch:
- een Startmenu-koppeling: `The One Family > The One Window`
- een bureaubladkoppeling: `The One Window`

Vanaf dat moment worden nieuwe Windows-versies alleen als in-app update over deze vaste installatie gezet.
