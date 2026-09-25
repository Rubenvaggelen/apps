namespace TheOneMain.Windows;

public static class BuildInfo
{
    // De GitHub Actions Windows-build overschrijft dit bestand tijdens het bouwen
    // met het echte runnummer. Lokaal blijft 0 een veilige developmentversie.
    public const int Version = 0;
}
