package com.vaultsurvival.plugin.updates;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateService {

    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final HttpClient httpClient;

    private ReleaseInfo cachedLatest;

    public UpdateService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public void check(CommandSender sender) {
        plugin.getScheduler().runAsync(() -> {
            try {
                ReleaseInfo latest = fetchLatestRelease();
                cachedLatest = latest;
                send(sender,
                    fmt.header("Vault Survival Update"),
                    fmt.info("Current: &e" + currentVersion()),
                    fmt.info("Latest: &e" + latest.tagName()),
                    fmt.info("Repo: &e" + repoSlug()),
                    fmt.info(isNewer(latest.tagName(), currentVersion())
                        ? "&aUpdate available. Use &e/vs update stage"
                        : "&aYou are on the latest known release."));
            } catch (Exception e) {
                send(sender, fmt.error("Update check failed: " + e.getMessage()));
            }
        });
    }

    public void stage(CommandSender sender) {
        plugin.getScheduler().runAsync(() -> {
            try {
                ReleaseInfo latest = cachedLatest != null ? cachedLatest : fetchLatestRelease();
                cachedLatest = latest;
                Files.createDirectories(stagedDir());
                Path stagedJar = stagedJarPath();
                download(latest.assetDownloadUrl(), stagedJar);
                send(sender,
                    fmt.success("Downloaded &e" + latest.assetName() + " &ato staged updates."),
                    fmt.info("Staged: &e" + stagedJar),
                    fmt.info("Use &e/vs update install &7to queue it for the next restart."));
            } catch (Exception e) {
                send(sender, fmt.error("Update stage failed: " + e.getMessage()));
            }
        });
    }

    public void install(CommandSender sender) {
        plugin.getScheduler().runAsync(() -> {
            try {
                Path stagedJar = stagedJarPath();
                if (!Files.exists(stagedJar)) {
                    send(sender, fmt.error("No staged update found. Use /vs update stage first."));
                    return;
                }
                Files.createDirectories(updateDir());
                Path updateJar = updateDir().resolve(plugin.getConfigManager().getUpdateAssetName());
                Files.copy(stagedJar, updateJar, StandardCopyOption.REPLACE_EXISTING);
                send(sender,
                    fmt.success("Queued update for next restart."),
                    fmt.info("Install path: &e" + updateJar),
                    fmt.warn("Restart the server to let Paper replace the loaded plugin jar."));
            } catch (Exception e) {
                send(sender, fmt.error("Update install failed: " + e.getMessage()));
            }
        });
    }

    public void status(CommandSender sender) {
        sender.sendMessage(fmt.header("Vault Survival Update Status"));
        sender.sendMessage(fmt.info("Repo: &e" + repoSlug()));
        sender.sendMessage(fmt.info("Asset: &e" + plugin.getConfigManager().getUpdateAssetName()));
        sender.sendMessage(fmt.info("Current: &e" + currentVersion()));
        sender.sendMessage(fmt.info("Staged jar: &e" + stagedJarPath()));
        sender.sendMessage(fmt.info("Paper update folder: &e" + updateDir()));
    }

    private ReleaseInfo fetchLatestRelease() throws IOException, InterruptedException {
        String json = getJson("https://api.github.com/repos/" + repoSlug() + "/releases/latest");
        String tag = extract(TAG_PATTERN, json, "tag_name");
        String assetName = plugin.getConfigManager().getUpdateAssetName();
        String assetUrl = extractAssetDownloadUrl(json, assetName);
        return new ReleaseInfo(tag, assetName, assetUrl);
    }

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VaultSurvival/" + currentVersion())
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void download(String url, Path destination) throws IOException, InterruptedException {
        Files.deleteIfExists(destination);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("User-Agent", "VaultSurvival/" + currentVersion())
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download returned HTTP " + response.statusCode());
        }
    }

    private String extractAssetDownloadUrl(String json, String assetName) throws IOException {
        Pattern assetPattern = Pattern.compile(
            "\\{[^{}]*\"name\"\\s*:\\s*\"" + Pattern.quote(assetName) + "\"[^{}]*\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"[^{}]*}",
            Pattern.DOTALL);
        Matcher matcher = assetPattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\/", "/");
        }
        {
            Pattern fallback = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]*" + Pattern.quote(assetName) + ")\"");
            matcher = fallback.matcher(json);
        }
        if (!matcher.find()) {
            throw new IOException("Release asset not found: " + assetName);
        }
        return matcher.group(1).replace("\\/", "/");
    }

    private String extract(Pattern pattern, String json, String field) throws IOException {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IOException("Missing GitHub release field: " + field);
        }
        return matcher.group(1);
    }

    private boolean isNewer(String latestTag, String current) {
        String latest = normalizeVersion(latestTag);
        String now = normalizeVersion(current);
        return compareVersions(latest, now) > 0;
    }

    private int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? parseVersionPart(left[i]) : 0;
            int r = i < right.length ? parseVersionPart(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private int parseVersionPart(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private String normalizeVersion(String version) {
        return version == null ? "0" : version.trim().replaceFirst("^[vV]", "");
    }

    private String currentVersion() {
        return plugin.getDescription().getVersion();
    }

    private String repoSlug() {
        return plugin.getConfigManager().getUpdateGithubOwner() + "/" + plugin.getConfigManager().getUpdateGithubRepo();
    }

    private Path stagedDir() {
        return plugin.getDataFolder().toPath().resolve("staged-updates");
    }

    private Path stagedJarPath() {
        return stagedDir().resolve(plugin.getConfigManager().getUpdateAssetName());
    }

    private Path updateDir() {
        Path pluginDir = plugin.getDataFolder().toPath().getParent();
        if (pluginDir == null) {
            pluginDir = Path.of("plugins");
        }
        return pluginDir.resolve("update");
    }

    private void send(CommandSender sender, String... messages) {
        plugin.getScheduler().runSync(() -> {
            for (String message : messages) {
                sender.sendMessage(message);
            }
        });
    }

    private record ReleaseInfo(String tagName, String assetName, String assetDownloadUrl) {
    }
}
