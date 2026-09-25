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
