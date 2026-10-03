using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace TheOneMain.Windows;

internal static class RemoteSleepService
{
    private const int Port = 38491;
    private const int Iterations = 120_000;

    // PBKDF2-HMAC-SHA256. Alleen salt + afgeleide hash worden opgeslagen.
    private static readonly byte[] PinSalt = Convert.FromHexString(
        "031507ef415e3d21765f1fcc73740631");
    private static readonly byte[] ExpectedPinHash = Convert.FromHexString(
        "b0caff5d9cc87fbe048db6f429b51da1a69bb3abcbdd8955d1f3290cd096a42e");

    private static int _started;

    public static void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0) return;
        _ = Task.Run(ListenAsync);
    }

    private static async Task ListenAsync()
    {
        TcpListener? listener = null;
        try
        {
            listener = new TcpListener(IPAddress.Any, Port);
            listener.Start();

            while (true)
            {
                var client = await listener.AcceptTcpClientAsync().ConfigureAwait(false);
                _ = Task.Run(() => HandleAsync(client));
            }
        }
        catch (SocketException)
        {
            // Een tweede The One Window-proces mag dezelfde poort niet claimen.
            // De eerste instantie blijft dan de slaapservice verzorgen.
        }
        catch
        {
            // De dashboard-app mag nooit crashen door de optionele luisterservice.
        }
        finally
        {
            try { listener?.Stop(); } catch { }
        }
    }

    private static async Task HandleAsync(TcpClient client)
    {
        using (client)
        {
            client.NoDelay = true;
            client.ReceiveTimeout = 4000;
            client.SendTimeout = 4000;

            using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, leaveOpen: true);
            using var writer = new StreamWriter(stream, new UTF8Encoding(false), 1024, leaveOpen: true)
            {
                AutoFlush = true,
                NewLine = "\n"
            };

            string? line;
            try { line = await reader.ReadLineAsync().ConfigureAwait(false); }
            catch { return; }

            if (string.IsNullOrWhiteSpace(line) || line.Length > 128)
            {
                await writer.WriteLineAsync("ERR COMMAND").ConfigureAwait(false);
                return;
            }

            var parts = line.Trim().Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length != 2 || !VerifyPin(parts[1]))
            {
                await writer.WriteLineAsync("ERR AUTH").ConfigureAwait(false);
                return;
            }

            var command = parts[0].ToUpperInvariant();
            if (command == "PING")
            {
                await writer.WriteLineAsync("OK PONG").ConfigureAwait(false);
                return;
            }

            if (command != "SLEEP")
            {
                await writer.WriteLineAsync("ERR COMMAND").ConfigureAwait(false);
                return;
            }

            await writer.WriteLineAsync("OK SLEEPING").ConfigureAwait(false);
            await writer.FlushAsync().ConfigureAwait(false);
            await Task.Delay(350).ConfigureAwait(false);

            try { SetSuspendState(false, false, false); } catch { }
        }
    }

    private static bool VerifyPin(string value)
    {
        byte[] actual;
        try
        {
            actual = Rfc2898DeriveBytes.Pbkdf2(
                value,
                PinSalt,
                Iterations,
                HashAlgorithmName.SHA256,
                32);
        }
        catch
        {
            return false;
        }

        return CryptographicOperations.FixedTimeEquals(actual, ExpectedPinHash);
    }

    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetSuspendState(
        [MarshalAs(UnmanagedType.Bool)] bool hibernate,
        [MarshalAs(UnmanagedType.Bool)] bool forceCritical,
        [MarshalAs(UnmanagedType.Bool)] bool disableWakeEvent);
}
