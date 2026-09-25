# The One Main — Windows

Native Windows rebuild of The One Main.

## Included
- The One dashboard style and tile layout
- Chrome tile and Chrome-first web launching
- Auto-start with Windows sign-in
- Mail & Calendar
- Route planner
- Household shopping list
- Films / Series / Music search shortcuts
- Parking addresses + end-time reminder
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

Android The One Main is not modified by this Windows project.

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
Bij de eerste start kopieert The One Main zichzelf automatisch naar:
`%LOCALAPPDATA%\Programs\The One Family\The One Main\TheOneMain.exe`

Daarna maakt de app automatisch:
- een Startmenu-koppeling: `The One Family > The One Main`
- een bureaubladkoppeling: `The One Main`

Vanaf dat moment worden nieuwe Windows-versies alleen als in-app update over deze vaste installatie gezet.
