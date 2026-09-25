using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace TheOneMain.Windows;

public sealed record HouseholdSyncResult(
    bool Connected,
    List<ShoppingItem> Items,
    string? Message = null);

public static class HouseholdSyncClient
{
    private const int DiscoveryPort = 38472;
    private const int TcpPort = 38471;
    private const string DiscoverMessage = "THE_ONE_DISCOVER_V1";
    private const string ReplyPrefix = "THE_ONE_HERE:";
    private const string Auth = "the-one-k2401-8ab8c3d0-v1";

    public static Task<HouseholdSyncResult> RefreshAsync() =>
        ExecuteAsync(null);

    public static Task<HouseholdSyncResult> AddAsync(string text) =>
        ExecuteAsync("HOUSEHOLD_ADD:" + Enc(text));

    public static Task<HouseholdSyncResult> ToggleAsync(string id) =>
        ExecuteAsync("HOUSEHOLD_TOGGLE:" + Enc(id));

    public static Task<HouseholdSyncResult> RemoveAsync(string id) =>
        ExecuteAsync("HOUSEHOLD_REMOVE:" + Enc(id));

    public static Task<HouseholdSyncResult> ClearDoneAsync() =>
        ExecuteAsync("HOUSEHOLD_CLEAR_DONE");

    private static async Task<HouseholdSyncResult> ExecuteAsync(string? command)
    {
        try
        {
            var endpoint = await DiscoverPhoneAsync();
            if (endpoint == null)
            {
                return new HouseholdSyncResult(
                    false,
                    new List<ShoppingItem>(),
                    "The One Main niet gevonden. Zorg dat telefoon en laptop op hetzelfde wifi-netwerk/hotspot zitten.");
            }

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(7));
            using var client = new TcpClient(AddressFamily.InterNetwork);
            await client.ConnectAsync(endpoint.Address, endpoint.Port, timeout.Token);
            client.NoDelay = true;

            await using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8, false, leaveOpen: true);
            await using var writer = new StreamWriter(stream, new UTF8Encoding(false), leaveOpen: true)
            {
                AutoFlush = true,
                NewLine = "\n"
            };

            await writer.WriteLineAsync("AUTH:" + Auth);

            // De telefoon stuurt direct na koppelen een actuele snapshot.
            var initial = await ReadSnapshotAsync(reader, timeout.Token);
            if (initial == null)
                throw new IOException("Geen Huishouden-data ontvangen van The One Main.");

            if (string.IsNullOrWhiteSpace(command))
                return new HouseholdSyncResult(true, initial);

            await writer.WriteLineAsync(command);

            // Iedere wijziging op The One Main resulteert opnieuw in een snapshot.
            var updated = await ReadSnapshotAsync(reader, timeout.Token) ?? initial;
            return new HouseholdSyncResult(true, updated);
        }
        catch (OperationCanceledException)
        {
            return new HouseholdSyncResult(
                false,
                new List<ShoppingItem>(),
                "Time-out bij The One Main. Controleer of beide apparaten op hetzelfde netwerk zitten.");
        }
        catch (Exception ex)
        {
            return new HouseholdSyncResult(
                false,
                new List<ShoppingItem>(),
                "Geen verbinding met The One Main: " + ex.Message);
        }
    }

    private static async Task<List<ShoppingItem>?> ReadSnapshotAsync(
        StreamReader reader,
        CancellationToken cancellationToken)
    {
        List<ShoppingItem>? items = null;

        while (!cancellationToken.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(cancellationToken);
            if (line == null) return items;

            if (line == "HOUSEHOLD_BEGIN")
            {
                items = new List<ShoppingItem>();
                continue;
            }

            if (items == null)
                continue;

            if (line == "HOUSEHOLD_END")
                return items;

            if (!line.StartsWith("HOUSEHOLD_ITEM:", StringComparison.Ordinal))
                continue;

            var parts = line.Split(':', 4);
            if (parts.Length != 4) continue;

            try
            {
                items.Add(new ShoppingItem
                {
                    Id = Dec(parts[1]),
                    Done = parts[2] == "1",
                    Text = Dec(parts[3])
                });
            }
            catch
            {
                // Eén beschadigd item mag de rest van de lijst niet blokkeren.
            }
        }

        return items;
    }

    private static async Task<IPEndPoint?> DiscoverPhoneAsync()
    {
        // Eerst het echte The One-discoveryprotocol gebruiken.
        var discovered = await DiscoverByBroadcastAsync();
        if (discovered != null)
            return discovered;

        // Bij een telefoonhotspot is de telefoon meestal de default gateway.
        foreach (var gateway in DefaultGateways())
        {
            if (await CanConnectAsync(gateway, TcpPort))
                return new IPEndPoint(gateway, TcpPort);
        }

        return null;
    }

    private static async Task<IPEndPoint?> DiscoverByBroadcastAsync()
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        udp.EnableBroadcast = true;

        var bytes = Encoding.UTF8.GetBytes(DiscoverMessage);
        foreach (var address in BroadcastAddresses())
        {
            try
            {
                await udp.SendAsync(
                    bytes,
                    bytes.Length,
                    new IPEndPoint(address, DiscoveryPort));
            }
            catch { }
        }

        using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(1800));
        while (!timeout.IsCancellationRequested)
        {
            try
            {
                var reply = await udp.ReceiveAsync(timeout.Token);
                var text = Encoding.UTF8.GetString(reply.Buffer).Trim();
                if (!text.StartsWith(ReplyPrefix, StringComparison.Ordinal))
                    continue;

                var port = int.TryParse(text[ReplyPrefix.Length..], out var parsed)
                    ? parsed
                    : TcpPort;
                return new IPEndPoint(reply.RemoteEndPoint.Address, port);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                break;
            }
        }

        return null;
    }

    private static IEnumerable<IPAddress> BroadcastAddresses()
    {
        var result = new HashSet<string> { IPAddress.Broadcast.ToString() };

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up)
                    continue;

                foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork ||
                        unicast.IPv4Mask == null)
                        continue;

                    var ip = unicast.Address.GetAddressBytes();
                    var mask = unicast.IPv4Mask.GetAddressBytes();
                    var broadcast = new byte[4];
                    for (var i = 0; i < 4; i++)
                        broadcast[i] = (byte)(ip[i] | (mask[i] ^ 255));

                    result.Add(new IPAddress(broadcast).ToString());
                }
            }
        }
        catch { }

        return result.Select(IPAddress.Parse);
    }

    private static IEnumerable<IPAddress> DefaultGateways()
    {
        var result = new List<IPAddress>();
        var seen = new HashSet<string>();

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up)
                    continue;

                foreach (var gateway in nic.GetIPProperties().GatewayAddresses)
                {
                    var address = gateway.Address;
                    if (address.AddressFamily != AddressFamily.InterNetwork ||
                        IPAddress.Any.Equals(address) ||
                        IPAddress.None.Equals(address))
                        continue;

                    if (seen.Add(address.ToString()))
                        result.Add(address);
                }
            }
        }
        catch { }

        return result;
    }

    private static async Task<bool> CanConnectAsync(IPAddress address, int port)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(700));
            using var client = new TcpClient(AddressFamily.InterNetwork);
            await client.ConnectAsync(address, port, timeout.Token);
            return client.Connected;
        }
        catch
        {
            return false;
        }
    }

    private static string Enc(string text) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

    private static string Dec(string value) =>
        Encoding.UTF8.GetString(Convert.FromBase64String(value));
}
