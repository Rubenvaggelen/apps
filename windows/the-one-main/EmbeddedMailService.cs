using System.IO;
using System.Windows;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace TheOneMain.Windows;

public static class EmbeddedMailService
{
    private const string MailUrl = "https://rubenvaggelen.github.io/Gmailorg/";
    private static CoreWebView2Environment? _environment;
    private static readonly SemaphoreSlim EnvironmentLock = new(1, 1);

    public static WebView2 CreateWebView() => new()
    {
        HorizontalAlignment = HorizontalAlignment.Stretch,
        VerticalAlignment = VerticalAlignment.Stretch,
        DefaultBackgroundColor = System.Drawing.Color.FromArgb(5, 7, 11)
    };

    public static async Task InitializeAsync(WebView2 web)
    {
        var environment = await GetEnvironmentAsync();
        await web.EnsureCoreWebView2Async(environment);

        web.CoreWebView2.Settings.AreDevToolsEnabled = false;
        web.CoreWebView2.Settings.IsStatusBarEnabled = false;

        web.NavigationCompleted += async (_, _) =>
        {
            await Task.Delay(300);
            await ApplyTheOneStyleAsync(web);
            await ShowInboxAsync(web);
        };

        web.Source = new Uri(MailUrl);
    }

    public static async Task ShowInboxAsync(WebView2 web) =>
        await ClickTabAsync(web, "inbox");

    public static async Task ShowCalendarAsync(WebView2 web) =>
        await ClickTabAsync(web, "calendar");

    public static async Task ShowAccountsAsync(WebView2 web) =>
        await ClickTabAsync(web, "accounts");

    public static async Task OpenComposeAsync(WebView2 web)
    {
        if (!await ReadyAsync(web)) return;
        await ApplyTheOneStyleAsync(web);
        await web.CoreWebView2.ExecuteScriptAsync(
            "document.getElementById('compose-btn')?.click();");
    }

    public static async Task RefreshAsync(WebView2 web)
    {
        if (!await ReadyAsync(web)) return;
        await web.CoreWebView2.ExecuteScriptAsync(
            "if (typeof refreshInbox === 'function') refreshInbox();");
    }

    private static async Task ClickTabAsync(WebView2 web, string view)
    {
        if (!await ReadyAsync(web)) return;
        await ApplyTheOneStyleAsync(web);
        var script = "document.querySelector('.tab[data-view=\"" +
                     view.Replace("\"", "") +
                     "\"]')?.click();";
        await web.CoreWebView2.ExecuteScriptAsync(script);
    }

    private static async Task ApplyTheOneStyleAsync(WebView2 web)
    {
        const string script = """
(() => {
  const styleId = 'the-one-window-style';
  if (document.getElementById(styleId)) return;
  const style = document.createElement('style');
  style.id = styleId;
  style.textContent = [
    ':root{--bg:#05070B!important;--surface:#0B111A!important;--surface-raised:#111722!important;',
    '--line:#243241!important;--text:#F3F8FC!important;--text-dim:#9FB2C2!important;',
    '--amber:#20B8FF!important;--amber-dim:#0B2533!important;--sage:#39D98A!important;}',
    'html,body,#app,main{background:#05070B!important;color:#F3F8FC!important;}',
    'header.tower,.tabbar{display:none!important;}',
    '#app{min-height:100vh!important;}',
    'main{padding:14px 18px 28px!important;}',
    '.message-row,.modal-card,.settings-group,.chip,.search-row input,.field input,.field textarea,.field select{border-color:#174963!important;}',
    '.btn-primary{background:#20B8FF!important;color:#031019!important;}',
    '.btn-ghost{border-color:#24495D!important;}',
    '.message-row:hover{border-color:#20B8FF!important;box-shadow:0 0 22px rgba(32,184,255,.16)!important;}',
    '#the-one-mail-settings-footer{margin:26px 0 8px;padding:18px;border:1px solid #174963;border-radius:16px;background:#071018;text-align:center;}',
    '#the-one-mail-settings-footer .the-one-mail-footer-title{color:#9FB2C2;font-size:12px;margin-bottom:10px;}',
    '#the-one-mail-settings-footer button{min-width:220px;padding:12px 20px;border-radius:12px;border:1px solid #20B8FF;background:#0B2533;color:#F3F8FC;font-weight:700;cursor:pointer;box-shadow:0 0 18px rgba(32,184,255,.12);}',
    '#the-one-mail-settings-footer button:hover{background:#10384B;box-shadow:0 0 24px rgba(32,184,255,.24);}',
    'h1,h2{font-family:system-ui,sans-serif!important;}'
  ].join('');
  document.head.appendChild(style);

  if (!document.getElementById('the-one-mail-settings-footer')) {
    const inboxView = document.getElementById('view-inbox');
    if (inboxView) {
      const footer = document.createElement('div');
      footer.id = 'the-one-mail-settings-footer';

      const title = document.createElement('div');
      title.className = 'the-one-mail-footer-title';
      title.textContent = 'THE ONE MAIL';

      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = '⚙  Instellingen';
      button.addEventListener('click', () => {
        const settingsTab = document.querySelector('.tab[data-view="settings"]');
        if (settingsTab) settingsTab.click();
      });

      footer.appendChild(title);
      footer.appendChild(button);
      inboxView.appendChild(footer);
    }
  }
})()
""";
        await web.CoreWebView2.ExecuteScriptAsync(script);
    }

    private static async Task<bool> ReadyAsync(WebView2 web)
    {
        if (web.CoreWebView2 == null)
        {
            try { await InitializeAsync(web); }
            catch { return false; }
        }

        return web.CoreWebView2 != null;
    }

    private static async Task<CoreWebView2Environment> GetEnvironmentAsync()
    {
        if (_environment != null) return _environment;

        await EnvironmentLock.WaitAsync();
        try
        {
            if (_environment != null) return _environment;

            var userData = Path.Combine(AppStore.BaseDirectory, "mail-webview");
            Directory.CreateDirectory(userData);
            _environment = await CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: userData);
            return _environment;
        }
        finally
        {
            EnvironmentLock.Release();
        }
    }
}
