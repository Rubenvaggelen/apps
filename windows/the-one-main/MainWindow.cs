using Microsoft.Win32;
using System.Diagnostics;
using System.Globalization;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace TheOneMain.Windows;

public sealed class MainWindow : Window
{
    private const string MailUrl = "https://rubenvaggelen.github.io/Gmailorg/";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(35) };

    private readonly Brush Bg = Brush("#12141C");
    private readonly Brush Surface = Brush("#1B1F2C");
    private readonly Brush SurfaceRaised = Brush("#232838");
    private readonly Brush Line = Brush("#2B3145");
    private readonly Brush TextMain = Brush("#ECEAE3");
    private readonly Brush TextDim = Brush("#8C93A8");
    private readonly Brush Amber = Brush("#E0A458");
    private readonly Brush Sage = Brush("#6FA287");

    private readonly Grid _content = new();
    private readonly TextBlock _clock = new();
    private SettingsData _settings;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(20) };

    private sealed record TileDef(string Id, string Label, string Icon, Action Open);

    public MainWindow()
    {
        _settings = AppStore.Load<SettingsData>("settings.json");
        StartupManager.SetEnabled(_settings.AutoStart);

        Title = "The One";
        Background = Bg;
        Foreground = TextMain;
        Width = 1320;
        Height = 820;
        MinWidth = 980;
        MinHeight = 650;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        if (_settings.StartMaximized) WindowState = WindowState.Maximized;

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Content = root;

        var top = BuildTopBar();
        Grid.SetRow(top, 0);
        root.Children.Add(top);

        _content.Background = Bg;
        Grid.SetRow(_content, 1);
        root.Children.Add(_content);

        _timer.Tick += (_, _) =>
        {
            _clock.Text = DateTime.Now.ToString("ddd d MMM  HH:mm", new CultureInfo("nl-NL"));
            CheckParkingReminder();
        };
        _timer.Start();
        _clock.Text = DateTime.Now.ToString("ddd d MMM  HH:mm", new CultureInfo("nl-NL"));

        AppStore.AddNotification("The One Main voor Windows gestart.");
        ShowHome();
    }

    private UIElement BuildTopBar()
    {
        var border = new Border
        {
            Background = Surface,
            BorderBrush = Line,
            BorderThickness = new Thickness(0, 0, 0, 1),
            Padding = new Thickness(18, 12, 18, 12)
        };

        var dock = new DockPanel();

        var home = SmallButton("⌂  Home", ShowHome);
        DockPanel.SetDock(home, Dock.Left);
        dock.Children.Add(home);

        _clock.Foreground = TextDim;
        _clock.FontSize = 14;
        _clock.VerticalAlignment = VerticalAlignment.Center;
        _clock.Margin = new Thickness(16, 0, 0, 0);
        DockPanel.SetDock(_clock, Dock.Right);
        dock.Children.Add(_clock);

        var title = new TextBlock
        {
            Text = "THE ONE",
            Foreground = Amber,
            FontSize = 30,
            FontWeight = FontWeights.Bold,
            FontFamily = new FontFamily("Georgia"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(224, 164, 88),
                BlurRadius = 10,
                Opacity = 0.35,
                ShadowDepth = 0
            }
        };
        dock.Children.Add(title);

        border.Child = dock;
        return border;
    }

    private void ShowHome()
    {
        var outer = new Grid { Background = Bg };
        var watermark = new TextBlock
        {
            Text = "THE ONE",
            FontFamily = new FontFamily("Georgia"),
            FontWeight = FontWeights.Bold,
            FontSize = 120,
            Foreground = new SolidColorBrush(Color.FromArgb(18, 224, 164, 88)),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false
        };
        outer.Children.Add(watermark);

        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(18)
        };
        var wrap = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center };
        scroll.Content = wrap;
        outer.Children.Add(scroll);

        var tiles = new List<TileDef>
        {
            new("notifications", "Meldingen", "🔔", ShowNotifications),
            new("mail", "Mail & Kalender", "✉", () => BrowserLauncher.OpenChrome(MailUrl)),
            new("route", "Route", "🗺", ShowRoute),
            new("household", "Huishouden", "🛒", ShowHousehold),
            new("movies", "Films, Series & Muziek", "▶", ShowMedia),
            new("parking", "Parkeren", "🅿", ShowParking),
            new("settings", "Instellingen", "⚙", ShowSettings),
            new("ask", "Vraag het", "?", ShowAsk),
            new("recipes", "Recepten", "🍽", ShowRecipes),
            new("news", "Nieuws", "📰", ShowNews),
            new("radio", "Radio", "📻", ShowRadio),
            new("currency", "Koers (EUR / SRD / USD)", "⇄", ShowCurrency),
            new("finance", "Financiën", "€", ShowFinance),
            new("lifestyle", "Lifestyle", "♥", ShowLifestyle),
            new("chrome", "Chrome", "🌐", () => BrowserLauncher.OpenChrome())
        };

        foreach (var tile in tiles)
        {
            if (_settings.HiddenTiles.Contains(tile.Id)) continue;
            wrap.Children.Add(BuildTile(tile.Id, tile.Label, tile.Icon, tile.Open, custom: false));
        }

        foreach (var app in _settings.CustomApps.ToList())
        {
            if (!File.Exists(app.ExePath)) continue;
            wrap.Children.Add(BuildTile(app.Id, app.Label, "◆", () => BrowserLauncher.OpenProgram(app.ExePath), custom: true));
        }

        wrap.Children.Add(BuildTile("add", "App toevoegen", "+", AddWindowsApp, custom: false, allowHide: false));
        _content.Children.Clear();
        _content.Children.Add(outer);
    }

    private Button BuildTile(string id, string label, string icon, Action action, bool custom, bool allowHide = true)
    {
        var button = new Button
        {
            Width = 225,
            Height = 145,
            Margin = new Thickness(8),
            Background = Surface,
            BorderBrush = Line,
            BorderThickness = new Thickness(1),
            Foreground = TextMain,
            Cursor = Cursors.Hand,
            Padding = new Thickness(14)
        };

        var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        stack.Children.Add(new TextBlock
        {
            Text = icon,
            FontSize = 38,
            Foreground = Amber,
            HorizontalAlignment = HorizontalAlignment.Center,
            FontFamily = new FontFamily("Segoe UI Emoji")
        });
        stack.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 15,
            Foreground = TextMain,
            TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(4, 10, 4, 0),
            MaxWidth = 185
        });
        button.Content = stack;
        button.Click += (_, _) => action();

        if (allowHide)
        {
            var menu = new ContextMenu();
            var item = new MenuItem { Header = custom ? "Snelkoppeling verwijderen" : "Tegel verbergen" };
            item.Click += (_, _) =>
            {
                if (custom)
                    _settings.CustomApps.RemoveAll(x => x.Id == id);
                else
                    _settings.HiddenTiles.Add(id);
                SaveSettings();
                ShowHome();
            };
            menu.Items.Add(item);
            button.ContextMenu = menu;
        }

        return button;
    }

    private ScrollViewer BeginPage(string title, string? subtitle, out StackPanel body)
    {
        body = new StackPanel
        {
            Margin = new Thickness(34, 24, 34, 40),
            MaxWidth = 1050,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        body.Children.Add(new TextBlock
        {
            Text = title,
            Foreground = Amber,
            FontSize = 30,
            FontFamily = new FontFamily("Georgia"),
            FontWeight = FontWeights.Bold,
            Margin = new Thickness(0, 0, 0, 8)
        });
        if (!string.IsNullOrWhiteSpace(subtitle))
            body.Children.Add(new TextBlock
            {
                Text = subtitle,
                Foreground = TextDim,
                FontSize = 14,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 0, 0, 18)
            });

        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = body,
            Background = Bg
        };
        _content.Children.Clear();
        _content.Children.Add(scroll);
        return scroll;
    }

    private Button ActionButton(string text, Action action, double width = 250)
    {
        var b = new Button
        {
            Content = text,
            Width = width,
            Height = 46,
            Margin = new Thickness(0, 5, 10, 5),
            Background = SurfaceRaised,
            Foreground = Amber,
            BorderBrush = Line,
            Cursor = Cursors.Hand,
            FontSize = 14
        };
        b.Click += (_, _) => action();
        return b;
    }

    private Button SmallButton(string text, Action action)
    {
        var b = new Button
        {
            Content = text,
            Height = 38,
            MinWidth = 95,
            Padding = new Thickness(12, 0, 12, 0),
            Background = SurfaceRaised,
            Foreground = TextMain,
            BorderBrush = Line,
            Cursor = Cursors.Hand
        };
        b.Click += (_, _) => action();
        return b;
    }

    private TextBox Input(string hint = "")
    {
        return new TextBox
        {
            MinHeight = 42,
            Margin = new Thickness(0, 5, 10, 5),
            Background = SurfaceRaised,
            Foreground = TextMain,
            BorderBrush = Line,
            CaretBrush = Amber,
            Padding = new Thickness(10),
            FontSize = 14,
            ToolTip = hint
        };
    }

    private TextBlock Label(string text, double size = 14, Brush? brush = null) =>
        new()
        {
            Text = text,
            FontSize = size,
            Foreground = brush ?? TextMain,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 5, 0, 5)
        };

    private Border Card(UIElement child)
    {
        return new Border
        {
            Background = Surface,
            BorderBrush = Line,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(16),
            Margin = new Thickness(0, 8, 0, 8),
            Child = child
        };
    }

    private void ShowNotifications()
    {
        BeginPage("Meldingen", "The One-meldingen op deze Windows-laptop.", out var body);
        var list = new StackPanel();
        body.Children.Add(Card(list));

        void Render()
        {
            list.Children.Clear();
            var items = AppStore.Load<List<OneNotification>>("notifications.json");
            if (items.Count == 0) list.Children.Add(Label("Geen meldingen.", 14, TextDim));
            foreach (var n in items.Take(50))
                list.Children.Add(Label($"{n.When:dd-MM HH:mm}  •  {n.Text}"));
        }
        Render();

        var row = new WrapPanel();
        row.Children.Add(ActionButton("Wis alle The One-meldingen", () =>
        {
            AppStore.Save("notifications.json", new List<OneNotification>());
            Render();
        }));
        row.Children.Add(ActionButton("Windows meldingsinstellingen", () =>
        {
            try { Process.Start(new ProcessStartInfo("ms-settings:notifications") { UseShellExecute = true }); } catch { }
        }));
        body.Children.Add(row);
    }

    private void ShowRoute()
    {
        BeginPage("Route", "Kies Van en Naar en open de route rechtstreeks in Chrome.", out var body);
        var from = Input("Van");
        from.Text = "Mijn locatie";
        var to = Input("Naar");
        body.Children.Add(Label("Van", 13, TextDim));
        body.Children.Add(from);
        body.Children.Add(Label("Naar", 13, TextDim));
        body.Children.Add(to);

        var row = new WrapPanel();
        row.Children.Add(ActionButton("Route openen", () =>
        {
            var origin = Uri.EscapeDataString(from.Text.Trim());
            var dest = Uri.EscapeDataString(to.Text.Trim());
            if (string.IsNullOrWhiteSpace(dest)) return;
            BrowserLauncher.OpenChrome($"https://www.google.com/maps/dir/?api=1&origin={origin}&destination={dest}");
        }));
        row.Children.Add(ActionButton("Van / Naar wisselen", () => (from.Text, to.Text) = (to.Text, from.Text)));
        row.Children.Add(ActionButton("The One routeplanner", () => BrowserLauncher.OpenChrome(MailUrl + "#route-standalone")));
        row.Children.Add(ActionButton("Brandstofprijzen", () => BrowserLauncher.OpenChrome("https://www.anwb.nl/auto/brandstof/brandstofprijzen")));
        body.Children.Add(row);
    }

    private void ShowHousehold()
    {
        BeginPage("Huishouden", "Dezelfde eenvoudige boodschappenlijst, lokaal opgeslagen op Windows.", out var body);
        var input = Input("Nieuw item");
        var add = ActionButton("Toevoegen", () => { });
        var top = new DockPanel();
        DockPanel.SetDock(add, Dock.Right);
        top.Children.Add(add);
        top.Children.Add(input);
        body.Children.Add(top);
        var listPanel = new StackPanel();
        body.Children.Add(Card(listPanel));

        void Render()
        {
            listPanel.Children.Clear();
            var items = AppStore.Load<List<ShoppingItem>>("shopping.json");
            if (items.Count == 0) listPanel.Children.Add(Label("Je lijst is leeg.", 14, TextDim));
            foreach (var item in items)
            {
                var row = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };
                var del = SmallButton("Verwijder", () =>
                {
                    var current = AppStore.Load<List<ShoppingItem>>("shopping.json");
                    current.RemoveAll(x => x.Id == item.Id);
                    AppStore.Save("shopping.json", current);
                    Render();
                });
                DockPanel.SetDock(del, Dock.Right);
                row.Children.Add(del);

                var check = new CheckBox
                {
                    Content = item.Text,
                    IsChecked = item.Done,
                    Foreground = item.Done ? TextDim : TextMain,
                    FontSize = 16,
                    VerticalAlignment = VerticalAlignment.Center
                };
                check.Checked += (_, _) =>
                {
                    var current = AppStore.Load<List<ShoppingItem>>("shopping.json");
                    var found = current.FirstOrDefault(x => x.Id == item.Id);
                    if (found != null) found.Done = true;
                    AppStore.Save("shopping.json", current);
                    Render();
                };
                check.Unchecked += (_, _) =>
                {
                    var current = AppStore.Load<List<ShoppingItem>>("shopping.json");
                    var found = current.FirstOrDefault(x => x.Id == item.Id);
                    if (found != null) found.Done = false;
                    AppStore.Save("shopping.json", current);
                    Render();
                };
                row.Children.Add(check);
                listPanel.Children.Add(row);
            }
        }

        add.Click -= null;
        add.Click += (_, _) =>
        {
            var text = input.Text.Trim();
            if (text.Length == 0) return;
            var items = AppStore.Load<List<ShoppingItem>>("shopping.json");
            items.Add(new ShoppingItem { Text = text });
            AppStore.Save("shopping.json", items);
            input.Clear();
            Render();
        };

        body.Children.Add(ActionButton("Afgeronde items wissen", () =>
        {
            var items = AppStore.Load<List<ShoppingItem>>("shopping.json");
            items.RemoveAll(x => x.Done);
            AppStore.Save("shopping.json", items);
            Render();
        }));
        Render();
    }

    private void ShowMedia()
    {
        BeginPage("Films, Series & Muziek", "Zoek vanuit The One en open resultaten in Chrome, of open je lokale media.", out var body);
        var search = Input("Titel, serie, artiest of nummer");
        body.Children.Add(search);
        var buttons = new WrapPanel();
        buttons.Children.Add(ActionButton("Zoek films & series", () =>
            BrowserLauncher.OpenChrome("https://www.google.com/search?q=" + Uri.EscapeDataString(search.Text + " film serie"))));
        buttons.Children.Add(ActionButton("Zoek op YouTube", () =>
            BrowserLauncher.OpenChrome("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(search.Text))));
        buttons.Children.Add(ActionButton("Pathé", () =>
            BrowserLauncher.OpenChrome("https://www.pathe.nl/zoek?q=" + Uri.EscapeDataString(search.Text))));
        buttons.Children.Add(ActionButton("Kinepolis", () =>
            BrowserLauncher.OpenChrome("https://kinepolis.nl/search/site/" + Uri.EscapeDataString(search.Text))));
        buttons.Children.Add(ActionButton("Muziekmap", () => BrowserLauncher.OpenFolder(Environment.SpecialFolder.MyMusic)));
        buttons.Children.Add(ActionButton("Videomap", () => BrowserLauncher.OpenFolder(Environment.SpecialFolder.MyVideos)));
        body.Children.Add(buttons);
    }

    private void ShowParking()
    {
        BeginPage("Parkeren", "Bewaar parkeeradressen en stel een eindtijdmelding in.", out var body);
        var data = AppStore.Load<ParkingData>("parking.json");
        var address = Input("Parkeeradres");
        var list = new StackPanel();

        var addRow = new DockPanel();
        var add = ActionButton("Adres bewaren", () =>
        {
            var value = address.Text.Trim();
            if (value.Length == 0) return;
            var p = AppStore.Load<ParkingData>("parking.json");
            if (!p.Addresses.Contains(value, StringComparer.OrdinalIgnoreCase)) p.Addresses.Add(value);
            AppStore.Save("parking.json", p);
            address.Clear();
            ShowParking();
        });
        DockPanel.SetDock(add, Dock.Right);
        addRow.Children.Add(add);
        addRow.Children.Add(address);
        body.Children.Add(addRow);

        foreach (var a in data.Addresses)
        {
            var row = new DockPanel();
            row.Children.Add(Label(a, 15));
            var remove = SmallButton("Verwijder", () =>
            {
                var p = AppStore.Load<ParkingData>("parking.json");
                p.Addresses.RemoveAll(x => string.Equals(x, a, StringComparison.OrdinalIgnoreCase));
                AppStore.Save("parking.json", p);
                ShowParking();
            });
            DockPanel.SetDock(remove, Dock.Right);
            row.Children.Add(remove);
            list.Children.Add(row);
        }
        body.Children.Add(Card(list));

        body.Children.Add(Label("Parkeer-eindtijd (HH:mm)", 13, TextDim));
        var time = Input("bijv. 18:30");
        time.Width = 180;
        time.HorizontalAlignment = HorizontalAlignment.Left;
        if (data.EndTime is DateTime existing && existing > DateTime.Now) time.Text = existing.ToString("HH:mm");
        body.Children.Add(time);

        var buttons = new WrapPanel();
        buttons.Children.Add(ActionButton("Melding instellen", () =>
        {
            if (!TimeSpan.TryParse(time.Text.Trim(), out var t)) return;
            var when = DateTime.Today.Add(t);
            if (when <= DateTime.Now) when = when.AddDays(1);
            var p = AppStore.Load<ParkingData>("parking.json");
            p.EndTime = when;
            p.EndTimeAlertShown = false;
            AppStore.Save("parking.json", p);
            AppStore.AddNotification($"Parkeermelding ingesteld voor {when:HH:mm}.");
            ShowParking();
        }));
        buttons.Children.Add(ActionButton("Eindtijd wissen", () =>
        {
            var p = AppStore.Load<ParkingData>("parking.json");
            p.EndTime = null;
            p.EndTimeAlertShown = false;
            AppStore.Save("parking.json", p);
            ShowParking();
        }));
        buttons.Children.Add(ActionButton("Aanmelden parkeren Amsterdam", () =>
            BrowserLauncher.OpenChrome("https://www.amsterdam.nl/parkeren/")));
        body.Children.Add(buttons);

        var latest = AppStore.Load<ParkingData>("parking.json");
        body.Children.Add(Label(latest.EndTime is DateTime end
            ? $"Melding actief voor {end:ddd d MMM HH:mm}"
            : "Geen eindtijd ingesteld.", 14, latest.EndTime == null ? TextDim : Sage));
    }

    private void ShowSettings()
    {
        BeginPage("Instellingen", "Windows-instellingen voor The One Main.", out var body);

        var auto = new CheckBox
        {
            Content = "The One automatisch starten bij Windows-aanmelding",
            IsChecked = _settings.AutoStart,
            Foreground = TextMain,
            FontSize = 15,
            Margin = new Thickness(0, 8, 0, 8)
        };
        body.Children.Add(auto);

        var max = new CheckBox
        {
            Content = "Gemaximaliseerd opstarten",
            IsChecked = _settings.StartMaximized,
            Foreground = TextMain,
            FontSize = 15,
            Margin = new Thickness(0, 8, 0, 15)
        };
        body.Children.Add(max);

        body.Children.Add(Label("Groq API-key voor Vraag het / Recepten", 13, TextDim));
        var key = new PasswordBox
        {
            Password = _settings.GroqApiKey,
            Height = 42,
            Background = SurfaceRaised,
            Foreground = TextMain,
            BorderBrush = Line,
            Padding = new Thickness(10),
            Margin = new Thickness(0, 5, 0, 15)
        };
        body.Children.Add(key);

        body.Children.Add(Label(BrowserLauncher.FindChrome() is string c
            ? $"Chrome gevonden: {c}"
            : "Chrome niet gevonden. Weblinks vallen terug op je standaardbrowser.", 13, TextDim));

        body.Children.Add(ActionButton("Instellingen opslaan", () =>
        {
            _settings.AutoStart = auto.IsChecked == true;
            _settings.StartMaximized = max.IsChecked == true;
            _settings.GroqApiKey = key.Password.Trim();
            SaveSettings();
            StartupManager.SetEnabled(_settings.AutoStart);
            AppStore.AddNotification("Instellingen opgeslagen.");
            MessageBox.Show("Instellingen opgeslagen.", "The One");
        }));

        body.Children.Add(Label("Verborgen tegels", 19, Amber));
        if (_settings.HiddenTiles.Count == 0)
            body.Children.Add(Label("Geen verborgen tegels.", 14, TextDim));
        foreach (var id in _settings.HiddenTiles.ToList())
        {
            body.Children.Add(ActionButton($"Herstel {id}", () =>
            {
                _settings.HiddenTiles.Remove(id);
                SaveSettings();
                ShowSettings();
            }));
        }
    }

    private void ShowAsk()
    {
        BeginPage("Vraag het", "Eén Nederlands antwoord via Groq, net als The One Main.", out var body);
        var question = Input("Wat wil je vragen?");
        question.AcceptsReturn = true;
        question.TextWrapping = TextWrapping.Wrap;
        question.Height = 90;
        var answer = new TextBox
        {
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            MinHeight = 230,
            Background = Surface,
            Foreground = TextMain,
            BorderBrush = Line,
            Padding = new Thickness(14),
            Margin = new Thickness(0, 15, 0, 10),
            FontSize = 15
        };
        body.Children.Add(question);
        var ask = ActionButton("Vraag het", () => { });
        body.Children.Add(ask);
        body.Children.Add(answer);
        body.Children.Add(Label("Bron: Groq", 12, TextDim));

        ask.Click += async (_, _) =>
        {
            if (string.IsNullOrWhiteSpace(_settings.GroqApiKey))
            {
                answer.Text = "Vul eerst je Groq API-key in bij Instellingen.";
                return;
            }
            ask.IsEnabled = false;
            answer.Text = "Bezig…";
            try
            {
                answer.Text = await CallGroq(
                    "Antwoord in het Nederlands. Geef één duidelijk, direct antwoord zonder meerdere keuzemodellen.",
                    question.Text.Trim());
            }
            catch (Exception ex) { answer.Text = "Kon geen antwoord ophalen: " + ex.Message; }
            finally { ask.IsEnabled = true; }
        };
    }

    private void ShowRecipes()
    {
        BeginPage("Recepten", "Zoek je vaste receptbronnen of laat The One één recept maken.", out var body);
        var query = Input("Wat wil je koken?");
        body.Children.Add(query);
        var result = new TextBox
        {
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 220,
            Background = Surface,
            Foreground = TextMain,
            BorderBrush = Line,
            Padding = new Thickness(14),
            Margin = new Thickness(0, 10, 0, 10)
        };
        var row = new WrapPanel();
        var generate = ActionButton("Maak recept", () => { });
        row.Children.Add(generate);
        row.Children.Add(ActionButton("Sranang Kukru", () =>
            BrowserLauncher.OpenChrome("https://www.google.com/search?q=" + Uri.EscapeDataString("site:sranangkukru.net " + query.Text))));
        row.Children.Add(ActionButton("Leuke Recepten Italiaans", () =>
            BrowserLauncher.OpenChrome("https://www.leukerecepten.nl/italiaanse-recepten/")));
        row.Children.Add(ActionButton("Leuke Recepten Hollands", () =>
            BrowserLauncher.OpenChrome("https://www.leukerecepten.nl/hollandse-recepten/")));
        body.Children.Add(row);
        body.Children.Add(result);

        generate.Click += async (_, _) =>
        {
            if (string.IsNullOrWhiteSpace(_settings.GroqApiKey))
            {
                result.Text = "Vul eerst je Groq API-key in bij Instellingen.";
                return;
            }
            generate.IsEnabled = false;
            result.Text = "Recept maken…";
            try
            {
                result.Text = await CallGroq(
                    "Je bent de receptenfunctie van The One. Geef precies één praktisch Nederlands recept met ingrediënten en werkwijze. Geen keuzelijst.",
                    query.Text.Trim());
            }
            catch (Exception ex) { result.Text = "Kon geen recept maken: " + ex.Message; }
            finally { generate.IsEnabled = true; }
        };
    }

    private void ShowNews()
    {
        BeginPage("Nieuws", "Dezelfde nieuwsbronnen als The One Main.", out var body);
        var row = new WrapPanel();
        foreach (var item in new[]
        {
            ("Starnieuws", "https://www.starnieuws.com/"),
            ("Waterkant", "https://www.waterkant.net/"),
            ("NU.nl", "https://www.nu.nl/"),
            ("NOS", "https://nos.nl/"),
            ("DWT", "https://dwtonline.com/")
        })
            row.Children.Add(ActionButton(item.Item1, () => BrowserLauncher.OpenChrome(item.Item2)));
        body.Children.Add(row);
    }

    private void ShowRadio()
    {
        BeginPage("Radio", "Open je zenders rechtstreeks in Chrome.", out var body);
        var row = new WrapPanel();
        foreach (var item in new[]
        {
            ("Radio 538", "https://www.538.nl/radio/luisteren"),
            ("3FM", "https://www.npo3fm.nl/"),
            ("FunX", "https://www.funx.nl/"),
            ("FunX Slowjamz", "https://www.funx.nl/slowjamz/online-radio-luisteren"),
            ("Sky Radio", "https://www.skyradio.nl/"),
            ("LIM FM", "https://www.limfmsu.com/"),
            ("Soul Radio", "https://soulradio.nl/"),
            ("Radio Veronica", "https://www.radioveronica.nl/"),
            ("Qmusic", "https://www.qmusic.nl/")
        })
            row.Children.Add(ActionButton(item.Item1, () => BrowserLauncher.OpenChrome(item.Item2)));
        body.Children.Add(row);
    }

    private void ShowCurrency()
    {
        BeginPage("Koers", "EUR, SRD en USD met live koers via dezelfde sleutelloze API als The One Main.", out var body);
        var source = new ComboBox { Width = 130, Height = 42, Margin = new Thickness(0, 5, 10, 5), ItemsSource = new[] { "EUR", "SRD", "USD" }, SelectedItem = "EUR" };
        var target = new ComboBox { Width = 130, Height = 42, Margin = new Thickness(0, 5, 10, 5), ItemsSource = new[] { "EUR", "SRD", "USD" }, SelectedItem = "SRD" };
        var amount = Input("Bedrag");
        amount.Width = 200;
        amount.HorizontalAlignment = HorizontalAlignment.Left;
        var status = Label("Koersen nog niet geladen.", 14, TextDim);
        var result = Label("—", 26, Amber);

        var selectors = new WrapPanel();
        selectors.Children.Add(source);
        selectors.Children.Add(target);
        selectors.Children.Add(amount);
        body.Children.Add(selectors);
        body.Children.Add(status);
        body.Children.Add(result);

        double eurSrd = double.NaN, eurUsd = double.NaN;

        void Calculate()
        {
            if (!double.TryParse(amount.Text.Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out var value)) { result.Text = "—"; return; }
            if (double.IsNaN(eurSrd) || double.IsNaN(eurUsd)) { result.Text = "Koers laden…"; return; }
            double PerEur(string c) => c switch { "EUR" => 1.0, "SRD" => eurSrd, "USD" => eurUsd, _ => 1.0 };
            var from = source.SelectedItem?.ToString() ?? "EUR";
            var to = target.SelectedItem?.ToString() ?? "SRD";
            var converted = value / PerEur(from) * PerEur(to);
            result.Text = $"{converted:0.00} {to}";
            status.Text = $"1 {from} = {PerEur(to) / PerEur(from):0.####} {to}";
        }

        async Task LoadRates()
        {
            try
            {
                status.Text = "Koersen ophalen…";
                var json = await Http.GetStringAsync("https://open.er-api.com/v6/latest/EUR");
                using var doc = JsonDocument.Parse(json);
                var rates = doc.RootElement.GetProperty("rates");
                eurSrd = rates.GetProperty("SRD").GetDouble();
                eurUsd = rates.GetProperty("USD").GetDouble();
                status.Foreground = TextMain;
                Calculate();
            }
            catch (Exception ex)
            {
                status.Text = "Ophalen mislukt: " + ex.Message;
                status.Foreground = TextDim;
            }
        }

        amount.TextChanged += (_, _) => Calculate();
        source.SelectionChanged += (_, _) => Calculate();
        target.SelectionChanged += (_, _) => Calculate();
        body.Children.Add(ActionButton("Koers vernieuwen", async () => await LoadRates()));
        _ = LoadRates();
    }

    private void ShowFinance()
    {
        BeginPage("Financiën", "Startbedrag minus uitgaven, met waarschuwing bij je ingestelde grens.", out var body);
        var data = AppStore.Load<FinanceData>("finance.json");
        var summary = Label("", 24, Amber);
        body.Children.Add(summary);

        var start = Input("Startbedrag");
        start.Text = data.StartAmount.ToString("0.00", CultureInfo.InvariantCulture);
        var threshold = Input("Waarschuwingsgrens");
        threshold.Text = data.WarningThreshold.ToString("0.00", CultureInfo.InvariantCulture);

        var settingsRow = new WrapPanel();
        start.Width = 180; threshold.Width = 180;
        settingsRow.Children.Add(start);
        settingsRow.Children.Add(threshold);
        settingsRow.Children.Add(ActionButton("Bedragen opslaan", () =>
        {
            var f = AppStore.Load<FinanceData>("finance.json");
            if (TryMoney(start.Text, out var s)) f.StartAmount = s;
            if (TryMoney(threshold.Text, out var t)) f.WarningThreshold = t;
            AppStore.Save("finance.json", f);
            ShowFinance();
        }, 180));
        body.Children.Add(settingsRow);

        var description = Input("Omschrijving");
        var amount = Input("Uitgave");
        amount.Width = 180;
        var addRow = new WrapPanel();
        description.Width = 360;
        addRow.Children.Add(description);
        addRow.Children.Add(amount);
        addRow.Children.Add(ActionButton("Uitgave toevoegen", () =>
        {
            if (!TryMoney(amount.Text, out var value) || value <= 0) return;
            var f = AppStore.Load<FinanceData>("finance.json");
            f.Transactions.Insert(0, new FinanceTransaction { Description = description.Text.Trim().IfBlank("Uitgave"), Amount = value });
            AppStore.Save("finance.json", f);
            ShowFinance();
        }, 190));
        body.Children.Add(addRow);

        var latest = AppStore.Load<FinanceData>("finance.json");
        var spent = latest.Transactions.Sum(x => x.Amount);
        var balance = latest.StartAmount - spent;
        summary.Text = $"Beschikbaar: € {balance:0.00}   •   Uitgaven: € {spent:0.00}";
        if (balance <= latest.WarningThreshold) summary.Foreground = Brushes.OrangeRed;

        var list = new StackPanel();
        foreach (var tx in latest.Transactions.Take(100))
        {
            var row = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
            var remove = SmallButton("Ongedaan", () =>
            {
                var f = AppStore.Load<FinanceData>("finance.json");
                f.Transactions.RemoveAll(x => x.Id == tx.Id);
                AppStore.Save("finance.json", f);
                ShowFinance();
            });
            DockPanel.SetDock(remove, Dock.Right);
            row.Children.Add(remove);
            row.Children.Add(Label($"{tx.When:dd-MM HH:mm}  •  {tx.Description}  •  - € {tx.Amount:0.00}", 15));
            list.Children.Add(row);
        }
        body.Children.Add(Card(list));
    }

    private void ShowLifestyle()
    {
        BeginPage("Lifestyle", "Fitness bijhouden en snel het weer voor wandelen of steppen bekijken.", out var body);
        var activity = Input("Activiteit, bijv. wandelen");
        var minutes = Input("Minuten");
        activity.Width = 300; minutes.Width = 140;
        var row = new WrapPanel();
        row.Children.Add(activity);
        row.Children.Add(minutes);
        row.Children.Add(ActionButton("Activiteit opslaan", () =>
        {
            if (!int.TryParse(minutes.Text.Trim(), out var m) || m <= 0) return;
            var entries = AppStore.Load<List<FitnessEntry>>("fitness.json");
            entries.Insert(0, new FitnessEntry { Activity = activity.Text.Trim().IfBlank("Training"), Minutes = m });
            AppStore.Save("fitness.json", entries);
            ShowLifestyle();
        }, 190));
        body.Children.Add(row);

        var quick = new WrapPanel();
        quick.Children.Add(ActionButton("Wandel-/stepweer", () => BrowserLauncher.OpenChrome("https://www.buienradar.nl/")));
        quick.Children.Add(ActionButton("Open-Meteo", () => BrowserLauncher.OpenChrome("https://open-meteo.com/")));
        body.Children.Add(quick);

        var list = new StackPanel();
        var logs = AppStore.Load<List<FitnessEntry>>("fitness.json");
        foreach (var e in logs.Take(50))
            list.Children.Add(Label($"{e.When:dd-MM}  •  {e.Activity}  •  {e.Minutes} min", 15));
        if (logs.Count == 0) list.Children.Add(Label("Nog geen activiteiten opgeslagen.", 14, TextDim));
        body.Children.Add(Card(list));
    }

    private void AddWindowsApp()
    {
        var dialog = new OpenFileDialog
        {
            Title = "Kies een Windows-programma",
            Filter = "Windows-programma (*.exe)|*.exe",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;

        var label = Path.GetFileNameWithoutExtension(dialog.FileName);
        if (string.IsNullOrWhiteSpace(label)) label = "App";
        _settings.CustomApps.Add(new CustomShortcut { Label = label, ExePath = dialog.FileName });
        SaveSettings();
        ShowHome();
    }

    private async Task<string> CallGroq(string system, string user)
    {
        var payload = JsonSerializer.Serialize(new
        {
            model = "llama-3.3-70b-versatile",
            temperature = 0.25,
            messages = new object[]
            {
                new { role = "system", content = system },
                new { role = "user", content = user }
            }
        });

        using var req = new HttpRequestMessage(HttpMethod.Post, "https://api.groq.com/openai/v1/chat/completions");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _settings.GroqApiKey);
        req.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var resp = await Http.SendAsync(req);
        var body = await resp.Content.ReadAsStringAsync();
        if (!resp.IsSuccessStatusCode) throw new Exception($"Groq HTTP {(int)resp.StatusCode}");
        using var doc = JsonDocument.Parse(body);
        return doc.RootElement.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString()?.Trim()
               ?? "Geen antwoord ontvangen.";
    }

    private void CheckParkingReminder()
    {
        var data = AppStore.Load<ParkingData>("parking.json");
        if (data.EndTime is not DateTime end || data.EndTimeAlertShown || end > DateTime.Now) return;
        data.EndTimeAlertShown = true;
        AppStore.Save("parking.json", data);
        AppStore.AddNotification("Je ingestelde parkeertijd is afgelopen.");
        MessageBox.Show("Je ingestelde parkeertijd is afgelopen.", "The One • Parkeren", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void SaveSettings() => AppStore.Save("settings.json", _settings);

    private static bool TryMoney(string text, out decimal value)
    {
        var normalized = text.Trim().Replace("€", "").Replace(" ", "").Replace(',', '.');
        return decimal.TryParse(normalized, NumberStyles.Number, CultureInfo.InvariantCulture, out value);
    }

    private static Brush Brush(string hex) => (Brush)new BrushConverter().ConvertFromString(hex)!;
}

internal static class StringHelpers
{
    public static string IfBlank(this string? value, string fallback) =>
        string.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
}
