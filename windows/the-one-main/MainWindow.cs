using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
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
using System.Windows.Media.Imaging;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace TheOneMain.Windows;

public sealed class MainWindow : Window
{
    private const string MailUrl = "https://rubenvaggelen.github.io/Gmailorg/";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(35) };

    private readonly Brush Bg = Brush("#05070B");
    private readonly Brush Surface = Brush("#0B111A");
    private readonly Brush SurfaceRaised = Brush("#111722");
    private readonly Brush Line = Brush("#243241");
    private readonly Brush TextMain = Brush("#F3F8FC");
    private readonly Brush TextDim = Brush("#9AA6B2");
    private readonly Brush Amber = Brush("#20B8FF");
    private readonly Brush Sage = Brush("#39D98A");

    private readonly Grid _content = new();
    private readonly TextBlock _clock = new();
    private SettingsData _settings;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(20) };

    private sealed record TileDef(string Id, string Label, string IconKey, Action Open);
    private sealed record StartMenuShortcut(string Label, string TargetPath);

    public MainWindow()
    {
        _settings = AppStore.Load<SettingsData>("settings.json");
        StartupManager.SetEnabled(_settings.AutoStart);

        Title = "The One Window";
        Background = Bg;
        Foreground = TextMain;
        Width = 1320;
        Height = 820;
        MinWidth = 980;
        MinHeight = 650;

        // The One Window opent altijd als echte full-screen dashboard-app.
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        WindowState = WindowState.Maximized;

        StateChanged += (_, _) =>
        {
            // Als Windows de app uit full-screen probeert te halen, zet hem direct terug.
            if (WindowState == WindowState.Normal)
                WindowState = WindowState.Maximized;
        };

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

        AppStore.AddNotification("The One Window gestart.");
        ShowHome();
    }

    private UIElement BuildTopBar()
    {
        var shell = new Border
        {
            Background = Surface,
            BorderBrush = Brush("#16394B"),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Padding = new Thickness(18, 9, 18, 9),
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255), BlurRadius = 14, Opacity = 0.12, ShadowDepth = 0
            }
        };
        var dock = new DockPanel();

        var brand = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        brand.Children.Add(CreateLogo(48));
        var brandText = new StackPanel { Margin = new Thickness(12,0,0,0), VerticalAlignment = VerticalAlignment.Center };
        brandText.Children.Add(new TextBlock
        {
            Text = "THE ONE", Foreground = Amber, FontSize = 23, FontWeight = FontWeights.Bold
        });
        brandText.Children.Add(new TextBlock
        {
            Text = "WINDOW", Foreground = TextMain, FontSize = 11,
            FontWeight = FontWeights.SemiBold, Margin = new Thickness(1,2,0,0)
        });
        brand.Children.Add(brandText);
        DockPanel.SetDock(brand, Dock.Left);
        dock.Children.Add(brand);

        var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        right.Children.Add(new TextBlock
        {
            Text = "THE ONE FAMILY", Foreground = Amber, FontSize = 10, FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0,0,18,0)
        });
        _clock.Foreground = TextDim;
        _clock.FontSize = 14;
        _clock.VerticalAlignment = VerticalAlignment.Center;
        _clock.Margin = new Thickness(0,0,14,0);
        right.Children.Add(_clock);
        right.Children.Add(SmallButton("⌂  Menu", ShowHome));
        DockPanel.SetDock(right, Dock.Right);
        dock.Children.Add(right);

        shell.Child = dock;
        return shell;
    }

    private void ShowHome()
    {
        var outer = new Grid { Background = Bg };
        outer.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        outer.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var watermark = CreateLogo(500, 0.055);
        watermark.HorizontalAlignment = HorizontalAlignment.Center;
        watermark.VerticalAlignment = VerticalAlignment.Center;
        watermark.IsHitTestVisible = false;
        Grid.SetRow(watermark, 0);
        outer.Children.Add(watermark);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(20,18,20,24) };
        var page = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, MaxWidth = 1260 };

        var hero = new Border
        {
            Background = Brush("#091018"), BorderBrush = Brush("#16394B"), BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14), Padding = new Thickness(22,16,22,16), Margin = new Thickness(8,0,8,14)
        };
        var heroRow = new DockPanel();
        var heroLogo = CreateLogo(74);
        DockPanel.SetDock(heroLogo, Dock.Left);
        heroRow.Children.Add(heroLogo);
        var heroText = new StackPanel { Margin = new Thickness(18,3,0,0), VerticalAlignment = VerticalAlignment.Center };
        heroText.Children.Add(new TextBlock
        {
            Text = "THE ONE WINDOW", Foreground = TextMain, FontSize = 30, FontWeight = FontWeights.Bold
        });
        heroText.Children.Add(new TextBlock
        {
            Text = "The One Family op Windows", Foreground = TextDim, FontSize = 14, Margin = new Thickness(0,4,0,0)
        });
        heroRow.Children.Add(heroText);
        hero.Child = heroRow;
        page.Children.Add(hero);

        var wrap = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center };
        page.Children.Add(wrap);
        scroll.Content = page;
        Grid.SetRow(scroll, 0);
        outer.Children.Add(scroll);

        var tiles = new List<TileDef>
        {
            // Zelfde iconen als The One Main / The One Car.
            new("notifications", "Meldingen", "notifications", ShowNotifications),
            new("mail", "Mail & Kalender", "mail", ShowMail),
            new("household", "Huishouden", "household", ShowHousehold),
            new("music", "Muziek", "music", ShowMusic),
            new("settings", "Instellingen", "settings", ShowSettings),
            new("ask", "Vraag het", "ask", ShowAsk),
            new("recipes", "Recepten", "recipes", ShowRecipes),
            new("news", "Nieuws", "news", ShowNews),
            new("radio", "Radio", "radio", ShowRadio),
            new("currency", "Koers (EUR / SRD / USD)", "currency", ShowCurrency),
            new("finance", "Financiën", "currency", ShowFinance),
            new("lifestyle", "Lifestyle", "lifestyle", ShowLifestyle),
            new("chrome", "Chrome", "chrome", () => BrowserLauncher.OpenChrome())
        };

        foreach (var tile in tiles)
        {
            if (_settings.HiddenTiles.Contains(tile.Id)) continue;
            wrap.Children.Add(BuildTile(tile.Id, tile.Label, tile.IconKey, tile.Open, custom: false));
        }

        EnsureRutuCompanyImported();

        foreach (var app in _settings.CustomApps.ToList())
        {
            if (!string.IsNullOrWhiteSpace(app.Url))
            {
                var url = app.Url;
                wrap.Children.Add(BuildTile(
                    app.Id,
                    app.Label,
                    WebsiteIconKey(url),
                    () => BrowserLauncher.OpenChrome(url),
                    custom: true,
                    iconOverride: CreateCustomWebsiteIcon(app, 70)));
                continue;
            }

            // Toegevoegde apps blijven permanent als tegel bewaard, ook als Windows
            // een snelkoppeling tijdelijk niet kan vinden. Alleen de gebruiker kan ze verwijderen.
            var target = app.ExePath;
            var isRutuCompany =
                app.Id == "rutu-bbq-bedrijf-windows" ||
                app.Label.Contains("Rutu", StringComparison.OrdinalIgnoreCase);

            wrap.Children.Add(BuildTile(
                app.Id,
                app.Label,
                "custom",
                () =>
                {
                    if (isRutuCompany)
                        RutuCompanyAppService.OpenImportedPath(target);
                    else
                        BrowserLauncher.OpenProgram(target);
                },
                custom: true,
                iconOverride: CreateCustomAppIcon(app, 70)));
        }

        wrap.Children.Add(BuildTile("add", "Tegel toevoegen", "add", ShowAddTileMenu, custom: false, allowHide: false));
        var footer = new Border
        {
            Background = Brush("#071018"),
            BorderBrush = Amber,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Margin = new Thickness(6, 4, 6, 8),
            Padding = new Thickness(16, 8, 16, 8),
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        footer.Child = new TextBlock
        {
            Text = "PART OF THE ONE FAMILY",
            Foreground = Amber,
            FontSize = 10,
            FontWeight = FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Left
        };
        Grid.SetRow(footer, 1);
        outer.Children.Add(footer);

        _content.Children.Clear();
        _content.Children.Add(outer);
    }

    private void EnsureRutuCompanyImported()
    {
        var install = RutuCompanyAppService.Detect();
        if (!install.Found || string.IsNullOrWhiteSpace(install.LauncherPath))
            return;

        var alreadyAdded = _settings.CustomApps.Any(app =>
            app.Id == "rutu-bbq-bedrijf-windows" ||
            app.Label.Contains("Rutu", StringComparison.OrdinalIgnoreCase) ||
            (!string.IsNullOrWhiteSpace(app.ExePath) &&
             string.Equals(app.ExePath, install.LauncherPath, StringComparison.OrdinalIgnoreCase)));

        if (alreadyAdded)
            return;

        _settings.CustomApps.Add(new CustomShortcut
        {
            Id = "rutu-bbq-bedrijf-windows",
            Label = "Rutu BBQ Bedrijf",
            ExePath = install.LauncherPath
        });
        SaveSettings();
        AppStore.AddNotification("Rutu BBQ Bedrijf toegevoegd aan The One Window.");
    }

    private Button BuildTile(
        string id,
        string label,
        string iconKey,
        Action action,
        bool custom,
        bool allowHide = true,
        UIElement? iconOverride = null)
    {
        var button = new Button
        {
            Width = 232,
            Height = 156,
            Margin = new Thickness(9),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Foreground = TextMain,
            Cursor = Cursors.Hand,
            Padding = new Thickness(0),
            FocusVisualStyle = null
        };

        var isChrome = id == "chrome";
        var isCustom = custom;
        var tileSurface = new Border
        {
            CornerRadius = new CornerRadius(18),
            BorderBrush = (isChrome || isCustom) ? Amber : Brush("#174963"),
            BorderThickness = new Thickness((isChrome || isCustom) ? 1.35 : 1),
            Padding = new Thickness(16),
            Background = (isChrome || isCustom)
                ? new LinearGradientBrush(Color.FromRgb(8, 31, 44), Color.FromRgb(5, 11, 18), 90)
                : new LinearGradientBrush(Color.FromRgb(11, 22, 32), Color.FromRgb(7, 12, 18), 90),
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = (isChrome || isCustom) ? Color.FromRgb(32, 184, 255) : Colors.Black,
                BlurRadius = (isChrome || isCustom) ? 22 : 18,
                Opacity = (isChrome || isCustom) ? 0.26 : 0.45,
                ShadowDepth = (isChrome || isCustom) ? 0 : 4
            }
        };

        var tileScale = new ScaleTransform(1.0, 1.0);
        tileSurface.RenderTransformOrigin = new Point(0.5, 0.5);
        tileSurface.RenderTransform = tileScale;

        var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };

        // De vaste tegels gebruiken exact dezelfde artwork-assets / vectorvormen
        // als de Android The One Main- en The One Car-tegels.
        stack.Children.Add(iconOverride ?? CreateHomeTileIcon(iconKey, 66));

        stack.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 14.5,
            FontWeight = FontWeights.SemiBold,
            Foreground = TextMain,
            TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(4, 10, 4, 0),
            MaxWidth = 192
        });

        var accent = new Border
        {
            Height = 2,
            Width = 42,
            CornerRadius = new CornerRadius(1),
            Background = Amber,
            Opacity = 0.75,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 9, 0, 0)
        };
        stack.Children.Add(accent);

        tileSurface.Child = stack;
        button.Content = tileSurface;
        button.Click += (_, _) => action();

        button.MouseEnter += (_, _) =>
        {
            var easeIn = new CubicEase { EasingMode = EasingMode.EaseOut };
            tileScale.BeginAnimation(
                ScaleTransform.ScaleXProperty,
                new DoubleAnimation(1.0, 1.035, TimeSpan.FromMilliseconds(140)) { EasingFunction = easeIn });
            tileScale.BeginAnimation(
                ScaleTransform.ScaleYProperty,
                new DoubleAnimation(1.0, 1.035, TimeSpan.FromMilliseconds(140)) { EasingFunction = easeIn });

            tileSurface.BorderBrush = Amber;
            tileSurface.Background = new LinearGradientBrush(
                Color.FromRgb(12, 39, 54),
                Color.FromRgb(8, 18, 27),
                90);
            tileSurface.Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255),
                BlurRadius = 24,
                Opacity = 0.28,
                ShadowDepth = 0
            };
        };
        button.MouseLeave += (_, _) =>
        {
            var easeOut = new CubicEase { EasingMode = EasingMode.EaseOut };
            tileScale.BeginAnimation(
                ScaleTransform.ScaleXProperty,
                new DoubleAnimation(tileScale.ScaleX, 1.0, TimeSpan.FromMilliseconds(170)) { EasingFunction = easeOut });
            tileScale.BeginAnimation(
                ScaleTransform.ScaleYProperty,
                new DoubleAnimation(tileScale.ScaleY, 1.0, TimeSpan.FromMilliseconds(170)) { EasingFunction = easeOut });

            tileSurface.BorderBrush = (isChrome || isCustom) ? Amber : Brush("#174963");
            tileSurface.Background = (isChrome || isCustom)
                ? new LinearGradientBrush(Color.FromRgb(8, 31, 44), Color.FromRgb(5, 11, 18), 90)
                : new LinearGradientBrush(Color.FromRgb(11, 22, 32), Color.FromRgb(7, 12, 18), 90);
            tileSurface.Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = (isChrome || isCustom) ? Color.FromRgb(32, 184, 255) : Colors.Black,
                BlurRadius = (isChrome || isCustom) ? 22 : 18,
                Opacity = (isChrome || isCustom) ? 0.26 : 0.45,
                ShadowDepth = (isChrome || isCustom) ? 0 : 4
            };
        };

        if (allowHide)
        {
            var menu = new ContextMenu();
            var item = new MenuItem { Header = custom ? "Snelkoppeling verwijderen" : "Tegel verbergen" };
            item.Click += (_, _) =>
            {
                if (custom) _settings.CustomApps.RemoveAll(x => x.Id == id);
                else _settings.HiddenTiles.Add(id);
                SaveSettings();
                ShowHome();
            };
            menu.Items.Add(item);
            button.ContextMenu = menu;
        }

        return button;
    }

    private UIElement CreateCustomAppIcon(CustomShortcut app, double size)
    {
        var source = CustomTileIconService.TryGetWindowsIcon(app.ExePath);
        return CreateTheOneLogoBadge(
            source,
            app.Label,
            size,
            CustomAccentColor(app.Id + app.Label));
    }

    private UIElement CreateCustomWebsiteIcon(CustomShortcut app, double size)
    {
        var accent = CustomAccentColor(app.Url + app.Label);
        var fallback = WebsiteIconKey(app.Url) switch
        {
            "youtube" => CreateWebIcon(size, true),
            _ => CreateTheOneLogoBadge(null, app.Label, size, accent)
        };

        var holder = new Grid
        {
            Width = size,
            Height = size,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        holder.Children.Add(fallback);

        var actual = new Image
        {
            Width = size * 0.58,
            Height = size * 0.58,
            Stretch = Stretch.Uniform,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        holder.Children.Add(actual);

        _ = LoadWebsiteLogoAsync(app.Url, actual);
        return holder;
    }

    private async Task LoadWebsiteLogoAsync(string url, Image image)
    {
        try
        {
            var source = await CustomTileIconService.GetWebsiteIconAsync(url);
            if (source == null) return;
            if (!image.Dispatcher.CheckAccess())
            {
                await image.Dispatcher.InvokeAsync(() => image.Source = source);
                return;
            }
            image.Source = source;
        }
        catch
        {
            // De stijlvolle fallback blijft gewoon zichtbaar.
        }
    }

    private UIElement CreateTheOneLogoBadge(ImageSource? source, string label, double size, Color accent)
    {
        var root = new Grid
        {
            Width = size,
            Height = size,
            HorizontalAlignment = HorizontalAlignment.Center
        };

        var accentBrush = new SolidColorBrush(accent);
        accentBrush.Freeze();

        root.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Fill = new LinearGradientBrush(
                Color.FromRgb(7, 28, 40),
                Color.FromRgb(4, 13, 20),
                90),
            Stroke = accentBrush,
            StrokeThickness = 1.7,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = accent,
                BlurRadius = 18,
                Opacity = 0.38,
                ShadowDepth = 0
            }
        });

        root.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Margin = new Thickness(size * 0.10),
            Fill = Brush("#09131D"),
            Stroke = Brush("#203645"),
            StrokeThickness = 1
        });

        if (source != null)
        {
            root.Children.Add(new Image
            {
                Source = source,
                Width = size * 0.58,
                Height = size * 0.58,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            });
        }
        else
        {
            var initials = MakeInitials(label);
            root.Children.Add(new TextBlock
            {
                Text = initials,
                Foreground = Brushes.White,
                FontSize = initials.Length > 1 ? size * 0.27 : size * 0.34,
                FontWeight = FontWeights.Bold,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                TextAlignment = TextAlignment.Center
            });
        }

        root.Children.Add(new Border
        {
            Width = size * 0.10,
            Height = size * 0.10,
            CornerRadius = new CornerRadius(size * 0.05),
            Background = accentBrush,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, size * 0.05, size * 0.05)
        });

        return root;
    }

    private static string MakeInitials(string label)
    {
        var parts = (label ?? "")
            .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length == 0) return "A";
        if (parts.Length == 1) return parts[0][..Math.Min(1, parts[0].Length)].ToUpperInvariant();
        return (parts[0][0].ToString() + parts[1][0]).ToUpperInvariant();
    }

    private static Color CustomAccentColor(string seed)
    {
        var palette = new[]
        {
            Color.FromRgb(32, 184, 255),
            Color.FromRgb(95, 205, 255),
            Color.FromRgb(88, 166, 255),
            Color.FromRgb(104, 225, 190),
            Color.FromRgb(156, 140, 255),
            Color.FromRgb(255, 167, 92)
        };

        unchecked
        {
            var hash = 17;
            foreach (var c in seed ?? "")
                hash = hash * 31 + c;
            var index = (hash & int.MaxValue) % palette.Length;
            return palette[index];
        }
    }

    private UIElement CreateHomeTileIcon(string iconKey, double size)
    {
        return iconKey switch
        {
            "notifications" => CreateAndroidVectorBadge("notifications", size),
            "route" => CreateAndroidVectorBadge("route", size),
            "lifestyle" => CreateAndroidVectorBadge("lifestyle", size),
            "add" => CreateAndroidVectorBadge("add", size),
            "mail" => CreateResourceTileIcon("ic_home_mail_fancy.png", size),
            "household" => CreateResourceTileIcon("ic_home_household_fancy.png", size),
            "movies" => CreateResourceTileIcon("ic_home_movies_fancy.png", size),
            "parking" => CreateResourceTileIcon("ic_home_parking_fancy.png", size),
            "settings" => CreateResourceTileIcon("ic_home_settings_fancy.png", size),
            "ask" => CreateResourceTileIcon("ic_home_ask_fancy.png", size),
            "recipes" => CreateResourceTileIcon("ic_home_recipes_fancy.png", size),
            "news" => CreateResourceTileIcon("ic_home_news_fancy.png", size),
            "radio" => CreateResourceTileIcon("ic_home_radio_fancy.png", size),
            "currency" => CreateResourceTileIcon("ic_home_currency_fancy.png", size),
            "music" => CreateResourceTileIcon("ic_home_music_fancy.png", size),
            "chrome" => CreateChromeIcon(size),
            "web" => CreateWebIcon(size, false),
            "youtube" => CreateWebIcon(size, true),
            _ => CreateFallbackTileIcon("◆", size)
        };
    }

    private Image CreateResourceTileIcon(string fileName, double size)
    {
        return new Image
        {
            Width = size,
            Height = size,
            Stretch = Stretch.Uniform,
            HorizontalAlignment = HorizontalAlignment.Center,
            Source = new BitmapImage(
                new Uri($"pack://application:,,,/Assets/Icons/{fileName}", UriKind.Absolute))
        };
    }

    private UIElement CreateChromeIcon(double size)
    {
        var root = new Grid
        {
            Width = size,
            Height = size,
            HorizontalAlignment = HorizontalAlignment.Center
        };

        var halo = new System.Windows.Shapes.Ellipse
        {
            Fill = new LinearGradientBrush(
                Color.FromRgb(10, 55, 76),
                Color.FromRgb(5, 20, 30),
                90),
            Stroke = Amber,
            StrokeThickness = 1.6,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255),
                BlurRadius = 20,
                Opacity = 0.42,
                ShadowDepth = 0
            }
        };
        root.Children.Add(halo);

        var globe = new Grid
        {
            Width = size * 0.58,
            Height = size * 0.58,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        globe.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Stroke = Brush("#F3F8FC"),
            StrokeThickness = 1.6,
            Fill = Brushes.Transparent
        });
        globe.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Width = size * 0.22,
            Stretch = Stretch.Fill,
            Stroke = Amber,
            StrokeThickness = 1.4,
            Fill = Brushes.Transparent,
            HorizontalAlignment = HorizontalAlignment.Center
        });
        globe.Children.Add(new Border
        {
            Height = 1.5,
            Background = Amber,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(3, 0, 3, 0)
        });
        root.Children.Add(globe);

        root.Children.Add(new Border
        {
            Width = size * 0.12,
            Height = size * 0.12,
            CornerRadius = new CornerRadius(size * 0.06),
            Background = Amber,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255),
                BlurRadius = 8,
                Opacity = 0.8,
                ShadowDepth = 0
            }
        });

        return root;
    }

    private UIElement CreateWebIcon(double size, bool video)
    {
        var root = new Grid
        {
            Width = size,
            Height = size,
            HorizontalAlignment = HorizontalAlignment.Center
        };

        root.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Fill = new LinearGradientBrush(
                Color.FromRgb(9, 45, 63),
                Color.FromRgb(5, 18, 27),
                90),
            Stroke = Amber,
            StrokeThickness = 1.4
        });

        if (video)
        {
            root.Children.Add(VectorPath(
                "M8,5 L20,12 L8,19 Z",
                "#F3F8FC",
                "#20B8FF",
                0.7,
                size * 0.48));
        }
        else
        {
            var globe = new Grid
            {
                Width = size * 0.50,
                Height = size * 0.50,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            globe.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Stroke = Brush("#F3F8FC"),
                StrokeThickness = 1.4
            });
            globe.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = size * 0.18,
                Stroke = Amber,
                StrokeThickness = 1.2,
                HorizontalAlignment = HorizontalAlignment.Center
            });
            globe.Children.Add(new Border
            {
                Height = 1.3,
                Background = Amber,
                VerticalAlignment = VerticalAlignment.Center
            });
            root.Children.Add(globe);
        }

        return root;
    }

    private UIElement CreateFallbackTileIcon(string glyph, double size)
    {
        var badge = new Border
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(size / 2),
            Background = Brush("#0B2533"),
            BorderBrush = Amber,
            BorderThickness = new Thickness(1.2),
            HorizontalAlignment = HorizontalAlignment.Center
        };
        badge.Child = new TextBlock
        {
            Text = glyph,
            FontSize = size * 0.43,
            Foreground = Amber,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            TextAlignment = TextAlignment.Center,
            FontFamily = new FontFamily("Segoe UI Emoji")
        };
        return badge;
    }

    private UIElement CreateAndroidVectorBadge(string kind, double size)
    {
        var root = new Grid
        {
            Width = size,
            Height = size,
            HorizontalAlignment = HorizontalAlignment.Center
        };

        if (kind == "add")
        {
            root.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Fill = Brush("#173D2B"),
                Stroke = Brush("#E0A458"),
                StrokeThickness = size * 1.5 / 56.0
            });
            root.Children.Add(VectorPath(
                "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
                "#F4D08A",
                null,
                0,
                size * 28.0 / 56.0));
            return root;
        }

        var outer = new LinearGradientBrush
        {
            StartPoint = new Point(0, 1),
            EndPoint = new Point(1, 0)
        };
        outer.GradientStops.Add(new GradientStop(ColorFrom("#F7D98B"), 0));
        outer.GradientStops.Add(new GradientStop(ColorFrom("#D89A3A"), 0.5));
        outer.GradientStops.Add(new GradientStop(ColorFrom("#8E5B14"), 1));
        root.Children.Add(new System.Windows.Shapes.Ellipse { Fill = outer });

        var innerGradient = new LinearGradientBrush
        {
            StartPoint = new Point(0, 1),
            EndPoint = new Point(1, 0)
        };
        innerGradient.GradientStops.Add(new GradientStop(ColorFrom("#2D5A43"), 0));
        innerGradient.GradientStops.Add(new GradientStop(ColorFrom("#10251C"), 1));
        var inset = size * 3.0 / 56.0;
        root.Children.Add(new System.Windows.Shapes.Ellipse
        {
            Margin = new Thickness(inset),
            Fill = innerGradient,
            Stroke = Brush("#F4D08A"),
            StrokeThickness = size / 56.0
        });

        if (kind == "notifications")
        {
            root.Children.Add(VectorPath(
                "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5S10.5,3.17 10.5,4v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
                "#FFF1C7",
                "#E0A458",
                0.65,
                size * 31.0 / 56.0));

            var dotSize = size * 10.0 / 56.0;
            var dotMargin = size * 4.0 / 56.0;
            root.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = dotSize,
                Height = dotSize,
                Fill = Brush("#D94B3D"),
                Stroke = Brush("#FFF1C7"),
                StrokeThickness = size / 56.0,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, dotMargin, dotMargin, 0)
            });
        }
        else if (kind == "route")
        {
            root.Children.Add(VectorPath(
                "M21.71,11.29l-9,-9a1,1 0,0 0,-1.42 0l-9,9a1,1 0,0 0,0 1.42l9,9a1,1 0,0 0,1.42 0l9,-9a1,1 0,0 0,0 -1.42zM14,14.5V12h-4v3H8v-4a1,1 0,0 1,1 -1h5V7.5l3.5,3.5z",
                "#FFF1C7",
                "#E0A458",
                0.55,
                size * 32.0 / 56.0));
        }
        else if (kind == "lifestyle")
        {
            var holder = new Grid
            {
                Width = size * 32.0 / 56.0,
                Height = size * 32.0 / 56.0,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            holder.Children.Add(VectorPathCore(
                "M12,21s-7.2,-4.35 -9.55,-8.38C0.4,9.1 2.13,5 6.18,5c2.16,0 3.52,1.19 4.32,2.2C11.3,6.19 12.66,5 14.82,5c4.05,0 5.78,4.1 3.73,7.62C16.2,16.65 12,21 12,21z",
                "#FFF1C7",
                null,
                0));
            holder.Children.Add(VectorPathCore(
                "M4.4,12h3.1l1.15,-2.5 2.1,5 1.4,-3h2.25",
                "#10251C",
                null,
                0));
            root.Children.Add(holder);
        }

        return root;
    }

    private UIElement VectorPath(string data, string fill, string? stroke, double strokeWidth, double itemSize)
    {
        var holder = new Grid
        {
            Width = itemSize,
            Height = itemSize,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        holder.Children.Add(VectorPathCore(data, fill, stroke, strokeWidth));
        return holder;
    }

    private UIElement VectorPathCore(string data, string fill, string? stroke, double strokeWidth)
    {
        try
        {
            var path = new System.Windows.Shapes.Path
            {
                Data = Geometry.Parse(data),
                Fill = Brush(fill),
                Stroke = string.IsNullOrWhiteSpace(stroke) ? null : Brush(stroke),
                StrokeThickness = strokeWidth,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch
            };
            return path;
        }
        catch
        {
            return new TextBlock
            {
                Text = "•",
                Foreground = Brush(fill),
                FontSize = 26,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
        }
    }

    private static Color ColorFrom(string hex) =>
        (Color)ColorConverter.ConvertFromString(hex);

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
            Text = title.ToUpperInvariant(),
            Foreground = Amber,
            FontSize = 28,
            FontFamily = new FontFamily("Segoe UI"),
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

    private Image CreateLogo(double size, double opacity = 1.0)
    {
        return new Image
        {
            Width = size, Height = size, Opacity = opacity, Stretch = Stretch.Uniform,
            Source = new BitmapImage(new Uri("pack://application:,,,/Assets/the_one_logo.png", UriKind.Absolute))
        };
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

    private void ShowMail()
    {
        _content.Children.Clear();

        var root = new Grid { Background = Bg };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var bar = new DockPanel
        {
            Background = Surface,
            Margin = new Thickness(18, 14, 18, 10),
            LastChildFill = false
        };

        var title = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        title.Children.Add(new TextBlock
        {
            Text = "THE ONE MAIL",
            Foreground = Amber,
            FontSize = 24,
            FontWeight = FontWeights.Bold
        });
        title.Children.Add(new TextBlock
        {
            Text = "Inbox, kalender en antwoorden direct in The One Window",
            Foreground = TextDim,
            FontSize = 12,
            Margin = new Thickness(0, 3, 0, 0)
        });
        DockPanel.SetDock(title, Dock.Left);
        bar.Children.Add(title);

        var actions = new WrapPanel
        {
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center
        };

        var inbox = ActionButton("Inbox", () => { }, 105);
        var calendar = ActionButton("Kalender", () => { }, 110);
        var compose = ActionButton("Nieuwe mail", () => { }, 130);
        var accounts = ActionButton("Accounts", () => { }, 110);
        var refresh = ActionButton("Vernieuwen", () => { }, 120);

        actions.Children.Add(inbox);
        actions.Children.Add(calendar);
        actions.Children.Add(compose);
        actions.Children.Add(accounts);
        actions.Children.Add(refresh);
        DockPanel.SetDock(actions, Dock.Right);
        bar.Children.Add(actions);

        Grid.SetRow(bar, 0);
        root.Children.Add(bar);

        var web = EmbeddedMailService.CreateWebView();
        Grid.SetRow(web, 1);
        root.Children.Add(web);

        inbox.Click += async (_, _) => await EmbeddedMailService.ShowInboxAsync(web);
        calendar.Click += async (_, _) => await EmbeddedMailService.ShowCalendarAsync(web);
        compose.Click += async (_, _) => await EmbeddedMailService.OpenComposeAsync(web);
        accounts.Click += async (_, _) => await EmbeddedMailService.ShowAccountsAsync(web);
        refresh.Click += async (_, _) => await EmbeddedMailService.RefreshAsync(web);

        _content.Children.Add(root);

        _ = EmbeddedMailService.InitializeAsync(web).ContinueWith(task =>
        {
            if (task.Exception == null) return;
            Dispatcher.Invoke(() =>
                MessageBox.Show(
                    "The One Mail kon niet starten. Controleer of Microsoft Edge WebView2 Runtime op Windows aanwezig is.",
                    "The One Mail"));
        });
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

    private void ShowMusic()
    {
        _content.Children.Clear();

        var root = new Grid { Background = Bg, Margin = new Thickness(22, 18, 22, 20) };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var header = new StackPanel { Margin = new Thickness(0, 0, 0, 14) };
        header.Children.Add(new TextBlock
        {
            Text = "MUZIEK",
            Foreground = Amber,
            FontSize = 28,
            FontWeight = FontWeights.Bold
        });
        header.Children.Add(new TextBlock
        {
            Text = "Zoek op YouTube en speel direct af in The One Window, of zoek hetzelfde nummer meteen op Spotify.",
            Foreground = TextDim,
            FontSize = 13,
            Margin = new Thickness(0, 4, 0, 0)
        });

        var searchRow = new DockPanel { Margin = new Thickness(0, 14, 0, 0) };
        var search = Input("Artiest of nummer");

        var searchButton = ActionButton("▶ YouTube", () => { }, 150);
        DockPanel.SetDock(searchButton, Dock.Right);
        searchRow.Children.Add(searchButton);

        var spotifyButton = ActionButton("● Spotify", () => { }, 140);
        spotifyButton.Background = Brush("#1DB954");
        spotifyButton.Foreground = Brushes.White;
        spotifyButton.BorderBrush = Brush("#1ED760");
        DockPanel.SetDock(spotifyButton, Dock.Right);
        searchRow.Children.Add(spotifyButton);

        searchRow.Children.Add(search);
        header.Children.Add(searchRow);

        var status = Label("Typ een nummer of artiest om te zoeken.", 12, TextDim);
        status.Margin = new Thickness(0, 8, 0, 0);
        header.Children.Add(status);

        Grid.SetRow(header, 0);
        root.Children.Add(header);

        var body = new Grid();
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.42, GridUnitType.Star) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.58, GridUnitType.Star) });

        var resultsScroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Margin = new Thickness(0, 0, 10, 0)
        };
        var results = new StackPanel();
        resultsScroll.Content = results;
        Grid.SetColumn(resultsScroll, 0);
        body.Children.Add(resultsScroll);

        var playerCard = new Border
        {
            Background = Surface,
            BorderBrush = Brush("#174963"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Margin = new Thickness(10, 0, 0, 0),
            Padding = new Thickness(12)
        };

        var playerGrid = new Grid();
        playerGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        playerGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var nowPlaying = new TextBlock
        {
            Text = "Kies links een nummer",
            Foreground = TextMain,
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(4, 0, 4, 10),
            TextWrapping = TextWrapping.Wrap
        };
        Grid.SetRow(nowPlaying, 0);
        playerGrid.Children.Add(nowPlaying);

        var web = YouTubeMusicService.CreatePlayer();
        Grid.SetRow(web, 1);
        playerGrid.Children.Add(web);

        playerCard.Child = playerGrid;
        Grid.SetColumn(playerCard, 1);
        body.Children.Add(playerCard);

        Grid.SetRow(body, 1);
        root.Children.Add(body);
        _content.Children.Add(root);

        async Task RunSearch()
        {
            var query = search.Text.Trim();
            if (query.Length == 0)
            {
                status.Text = "Vul eerst een artiest of nummer in.";
                status.Foreground = Amber;
                return;
            }

            searchButton.IsEnabled = false;
            results.Children.Clear();
            status.Text = "Zoeken op YouTube…";
            status.Foreground = TextDim;

            try
            {
                var found = await YouTubeMusicService.SearchAsync(query);
                results.Children.Clear();

                if (found.Count == 0)
                {
                    status.Text = "Geen nummers gevonden.";
                    status.Foreground = Amber;
                    return;
                }

                status.Text = $"{found.Count} resultaten gevonden";
                status.Foreground = Sage;

                foreach (var item in found)
                {
                    var captured = item;
                    var card = new Button
                    {
                        Background = Surface,
                        BorderBrush = Brush("#174963"),
                        BorderThickness = new Thickness(1),
                        Padding = new Thickness(12),
                        Margin = new Thickness(0, 0, 0, 9),
                        HorizontalContentAlignment = HorizontalAlignment.Stretch,
                        Cursor = Cursors.Hand,
                        FocusVisualStyle = null
                    };

                    var row = new Grid();
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(110) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

                    var thumb = YouTubeMusicService.CreateThumbnail(captured.ThumbnailUrl);
                    thumb.Margin = new Thickness(0, 0, 12, 0);
                    Grid.SetColumn(thumb, 0);
                    row.Children.Add(thumb);

                    var meta = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    meta.Children.Add(new TextBlock
                    {
                        Text = captured.Title,
                        Foreground = TextMain,
                        FontSize = 14,
                        FontWeight = FontWeights.SemiBold,
                        TextWrapping = TextWrapping.Wrap
                    });
                    meta.Children.Add(new TextBlock
                    {
                        Text = captured.Channel,
                        Foreground = TextDim,
                        FontSize = 11,
                        Margin = new Thickness(0, 5, 0, 0),
                        TextTrimming = TextTrimming.CharacterEllipsis
                    });
                    Grid.SetColumn(meta, 1);
                    row.Children.Add(meta);

                    card.Content = row;
                    card.MouseEnter += (_, _) =>
                    {
                        card.BorderBrush = Amber;
                        card.Background = SurfaceRaised;
                    };
                    card.MouseLeave += (_, _) =>
                    {
                        card.BorderBrush = Brush("#174963");
                        card.Background = Surface;
                    };

                    card.Click += async (_, _) =>
                    {
                        nowPlaying.Text = $"{captured.Title}  •  {captured.Channel}";
                        await YouTubeMusicService.PlayAsync(web, captured.VideoId);
                    };

                    results.Children.Add(card);
                }
            }
            catch (Exception ex)
            {
                status.Text = "Zoeken mislukt: " + ex.Message;
                status.Foreground = Amber;
            }
            finally
            {
                searchButton.IsEnabled = true;
            }
        }

        searchButton.Click += async (_, _) => await RunSearch();

        spotifyButton.Click += (_, _) =>
        {
            var query = search.Text.Trim();
            if (query.Length == 0)
            {
                status.Text = "Vul eerst een artiest of nummer in.";
                status.Foreground = Amber;
                return;
            }

            status.Text = "Spotify openen voor “" + query + "”…";
            status.Foreground = Brush("#1ED760");
            BrowserLauncher.OpenSpotifySearch(query);
        };

        search.KeyDown += async (_, e) =>
        {
            if (e.Key != Key.Enter) return;
            e.Handled = true;
            await RunSearch();
        };

        _ = YouTubeMusicService.InitializeAsync(web);
    }

    private void ShowHousehold()
    {
        BeginPage(
            "Huishouden",
            "Gedeelde boodschappenlijst van The One Window, The One Main en The One Car.",
            out var body);

        var status = Label("Verbinden met The One Main…", 13, TextDim);
        body.Children.Add(status);

        var input = Input("Nieuw item");
        var add = ActionButton("Toevoegen", () => { }, 150);
        var refresh = ActionButton("Synchroniseren", () => { }, 150);

        var top = new DockPanel();
        DockPanel.SetDock(refresh, Dock.Right);
        DockPanel.SetDock(add, Dock.Right);
        top.Children.Add(refresh);
        top.Children.Add(add);
        top.Children.Add(input);
        body.Children.Add(top);

        var listPanel = new StackPanel();
        body.Children.Add(Card(listPanel));

        var current = AppStore.Load<List<ShoppingItem>>("shopping.json");
        var operationRunning = false;

        void Render()
        {
            listPanel.Children.Clear();

            if (current.Count == 0)
            {
                listPanel.Children.Add(Label("Je boodschappenlijst is leeg.", 14, TextDim));
                return;
            }

            foreach (var item in current)
            {
                var captured = item;
                var row = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };

                var del = SmallButton("Verwijder", () => { });
                DockPanel.SetDock(del, Dock.Right);
                row.Children.Add(del);

                var check = new CheckBox
                {
                    Content = captured.Text,
                    IsChecked = captured.Done,
                    Foreground = captured.Done ? TextDim : TextMain,
                    FontSize = 16,
                    VerticalAlignment = VerticalAlignment.Center
                };
                row.Children.Add(check);
                listPanel.Children.Add(row);

                del.Click += async (_, _) =>
                {
                    if (operationRunning) return;
                    await RunSync(
                        () => HouseholdSyncClient.RemoveAsync(captured.Id),
                        "Item verwijderen…");
                };

                check.Checked += async (_, _) =>
                {
                    if (operationRunning || captured.Done) return;
                    await RunSync(
                        () => HouseholdSyncClient.ToggleAsync(captured.Id),
                        "Boodschappenlijst bijwerken…");
                };

                check.Unchecked += async (_, _) =>
                {
                    if (operationRunning || !captured.Done) return;
                    await RunSync(
                        () => HouseholdSyncClient.ToggleAsync(captured.Id),
                        "Boodschappenlijst bijwerken…");
                };
            }
        }

        async Task RunSync(
            Func<Task<HouseholdSyncResult>> operation,
            string busyText,
            bool clearInputOnSuccess = false)
        {
            if (operationRunning) return;
            operationRunning = true;
            add.IsEnabled = false;
            refresh.IsEnabled = false;
            status.Text = busyText;
            status.Foreground = TextDim;

            try
            {
                var result = await operation();
                if (result.Connected)
                {
                    current = result.Items;
                    AppStore.Save("shopping.json", current);
                    status.Text = "✓ Gekoppeld aan The One Main • wijzigingen worden ook naar The One Car gesynchroniseerd";
                    status.Foreground = Sage;
                    if (clearInputOnSuccess) input.Clear();
                    Render();
                }
                else
                {
                    status.Text = result.Message ??
                        "Geen verbinding met The One Main. De laatst gesynchroniseerde lijst blijft zichtbaar.";
                    status.Foreground = Amber;
                }
            }
            catch (Exception ex)
            {
                status.Text = "Synchronisatie mislukt: " + ex.Message;
                status.Foreground = Amber;
            }
            finally
            {
                operationRunning = false;
                add.IsEnabled = true;
                refresh.IsEnabled = true;
            }
        }

        add.Click += async (_, _) =>
        {
            var text = input.Text.Trim();
            if (text.Length == 0 || operationRunning) return;
            await RunSync(
                () => HouseholdSyncClient.AddAsync(text),
                "Item toevoegen en synchroniseren…",
                clearInputOnSuccess: true);
        };

        refresh.Click += async (_, _) =>
        {
            if (operationRunning) return;
            await RunSync(
                HouseholdSyncClient.RefreshAsync,
                "Synchroniseren met The One Main…");
        };

        var clearDone = ActionButton("Afgeronde items wissen", () => { });
        clearDone.Click += async (_, _) =>
        {
            if (operationRunning) return;
            await RunSync(
                HouseholdSyncClient.ClearDoneAsync,
                "Afgeronde items wissen…");
        };
        body.Children.Add(clearDone);

        Render();
        _ = RunSync(HouseholdSyncClient.RefreshAsync, "Synchroniseren met The One Main…");
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
        BeginPage("Instellingen", "Windows-instellingen voor The One Window.", out var body);

        var auto = new CheckBox
        {
            Content = "The One automatisch starten bij Windows-aanmelding",
            IsChecked = _settings.AutoStart,
            Foreground = TextMain,
            FontSize = 15,
            Margin = new Thickness(0, 8, 0, 8)
        };
        body.Children.Add(auto);

        body.Children.Add(Label(
            "The One Window opent altijd in volledig scherm.",
            13,
            TextDim));

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
            _settings.StartMaximized = true;
            _settings.GroqApiKey = key.Password.Trim();
            SaveSettings();
            StartupManager.SetEnabled(_settings.AutoStart);
            AppStore.AddNotification("Instellingen opgeslagen.");
            MessageBox.Show("Instellingen opgeslagen.", "The One");
        }));

        body.Children.Add(ActionButton("Controleer op Windows-updates", async () =>
        {
            await WindowsUpdateService.CheckForUpdateAsync(this, silentIfCurrent: false);
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

    private void ShowAddTileMenu()
    {
        BeginPage(
            "Tegel toevoegen",
            "Kies een app uit het Windows-startmenu of voeg een webpagina toe. Toegevoegde tegels blijven permanent bewaard.",
            out var body);

        body.Children.Add(Label("STARTMENU", 17, Amber));
        body.Children.Add(Label(
            "Dit is je echte Windows-startmenu-lijst. Zoek een app en voeg hem direct als tegel toe.",
            13,
            TextDim));

        var search = Input("Zoek in Startmenu");
        body.Children.Add(search);

        var startMenuList = new StackPanel();
        body.Children.Add(Card(startMenuList));

        var shortcuts = GetStartMenuShortcuts();

        void RenderStartMenu()
        {
            startMenuList.Children.Clear();
            var query = search.Text.Trim();

            var visible = shortcuts
                .Where(x => query.Length == 0 ||
                            x.Label.Contains(query, StringComparison.OrdinalIgnoreCase))
                .Take(100)
                .ToList();

            if (visible.Count == 0)
            {
                startMenuList.Children.Add(Label("Geen Startmenu-apps gevonden.", 14, TextDim));
                return;
            }

            foreach (var shortcut in visible)
            {
                var captured = shortcut;
                var row = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };

                var add = SmallButton("Toevoegen", () => AddStartMenuTile(captured));
                DockPanel.SetDock(add, Dock.Right);
                row.Children.Add(add);

                var label = Label(captured.Label, 15, TextMain);
                label.VerticalAlignment = VerticalAlignment.Center;
                row.Children.Add(label);
                startMenuList.Children.Add(row);
            }

            if (shortcuts.Count > visible.Count && query.Length == 0)
                startMenuList.Children.Add(Label("Typ hierboven om in alle Startmenu-apps te zoeken.", 12, TextDim));
        }

        search.TextChanged += (_, _) => RenderStartMenu();
        RenderStartMenu();

        body.Children.Add(new Border
        {
            Height = 1,
            Background = Brush("#16394B"),
            Margin = new Thickness(0, 24, 0, 18)
        });

        body.Children.Add(Label("WEBPAGINA", 17, Amber));
        body.Children.Add(Label(
            "Voeg een website toe als eigen The One-tegel. YouTube krijgt automatisch een eigen video-icoon.",
            13,
            TextDim));

        var websiteName = Input("Naam, bijvoorbeeld YouTube");
        var websiteUrl = Input("Webadres, bijvoorbeeld youtube.com");
        body.Children.Add(websiteName);
        body.Children.Add(websiteUrl);

        var quick = new WrapPanel();
        quick.Children.Add(ActionButton("YouTube invullen", () =>
        {
            websiteName.Text = "YouTube";
            websiteUrl.Text = "https://www.youtube.com/";
        }, 170));
        body.Children.Add(quick);

        body.Children.Add(ActionButton("Webpagina toevoegen", () =>
        {
            var raw = websiteUrl.Text.Trim();
            if (string.IsNullOrWhiteSpace(raw))
            {
                MessageBox.Show("Vul eerst een webadres in.", "The One Window");
                return;
            }

            if (!raw.Contains("://", StringComparison.Ordinal))
                raw = "https://" + raw;

            if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri) ||
                (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            {
                MessageBox.Show("Dit is geen geldig webadres.", "The One Window");
                return;
            }

            var label = websiteName.Text.Trim();
            if (string.IsNullOrWhiteSpace(label))
            {
                label = uri.Host;
                if (label.StartsWith("www.", StringComparison.OrdinalIgnoreCase))
                    label = label[4..];
            }

            _settings.CustomApps.Add(new CustomShortcut
            {
                Label = label,
                Url = uri.ToString()
            });
            SaveSettings();
            AppStore.AddNotification($"Webtegel '{label}' toegevoegd.");
            ShowHome();
        }));
    }

    private void AddStartMenuTile(StartMenuShortcut shortcut)
    {
        if (_settings.CustomApps.Any(x =>
                string.Equals(x.ExePath, shortcut.TargetPath, StringComparison.OrdinalIgnoreCase)))
        {
            MessageBox.Show("Deze Startmenu-app staat al als tegel op je startscherm.", "The One Window");
            return;
        }

        _settings.CustomApps.Add(new CustomShortcut
        {
            Label = shortcut.Label,
            ExePath = shortcut.TargetPath
        });
        SaveSettings();
        AppStore.AddNotification($"Tegel '{shortcut.Label}' toegevoegd.");
        ShowHome();
    }

    private static List<StartMenuShortcut> GetStartMenuShortcuts()
    {
        var result = new List<StartMenuShortcut>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var folder in new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.Programs),
            Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms)
        })
        {
            if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder))
                continue;

            List<string> files;
            try
            {
                files = Directory.EnumerateFiles(folder, "*", SearchOption.AllDirectories)
                    .Where(path =>
                        path.EndsWith(".lnk", StringComparison.OrdinalIgnoreCase) ||
                        path.EndsWith(".url", StringComparison.OrdinalIgnoreCase) ||
                        path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                    .ToList();
            }
            catch
            {
                continue;
            }

            foreach (var file in files)
            {
                var label = Path.GetFileNameWithoutExtension(file).Trim();
                if (string.IsNullOrWhiteSpace(label) || !seen.Add(label))
                    continue;

                result.Add(new StartMenuShortcut(label, file));
            }
        }

        return result
            .OrderBy(x => x.Label, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
    }

    private static string WebsiteIconKey(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri) &&
            uri.Host.Contains("youtube", StringComparison.OrdinalIgnoreCase))
            return "youtube";

        return "web";
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
