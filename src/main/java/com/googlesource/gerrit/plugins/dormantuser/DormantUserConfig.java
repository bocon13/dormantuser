package com.googlesource.gerrit.plugins.dormantuser;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAmount;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class DormantUserConfig {
    private static final String CONFIG_STATUS_DORMANT = "statusDormant";
    private static final String CONFIG_STATUS_DEFAULT = "statusDefault";
    private static final String CONFIG_PERIOD_DORMANT = "periodDormant";
    private static final String CONFIG_PERIOD_POLLING = "periodPolling";
    private static final String CONFIG_EPOCH = "epoch";


    private static final String DEFAULT_STATUS_EMPTY = "";
    private static final String DEFAULT_STATUS_DORMANT = "dormant";
    private static final TemporalAmount DEFAULT_DORMANT_PERIOD = Duration.ofDays(60); // 2 months
    private static final TemporalAmount DEFAULT_POLLING_PERIOD = Duration.ofHours(1);

    private final String statusDormant;
    private final String statusDefault;
    private final TemporalAmount dormantPeriod;
    private final TemporalAmount pollingPeriod;
    private final Instant epoch;

    private final Logger log = LoggerFactory.getLogger(DormantUserConfig.class);

    @Inject
    public DormantUserConfig(@PluginName String pluginName,
                             SitePaths sitePaths) {
        File configFile = sitePaths.gerrit_config.toFile();
        String statusDormant = null, statusDefault = null;
        TemporalAmount dormantPeriod = null, pollingPeriod = null;
        Instant epoch = null;
        try (EditablePluginConfig config = EditablePluginConfig.fromFile(pluginName, configFile)) {
            statusDormant = config.getString(CONFIG_STATUS_DORMANT);
            statusDefault = config.getString(CONFIG_STATUS_DEFAULT);
            dormantPeriod = config.getDuration(CONFIG_PERIOD_DORMANT);
            pollingPeriod = config.getDuration(CONFIG_PERIOD_POLLING);
            epoch = getOrNow(config, CONFIG_EPOCH, true);
        } catch (ConfigInvalidException e) {
            e.printStackTrace(); //FIXME
        } catch (IOException e) {
            e.printStackTrace(); //FIXME
        }

        this.statusDormant = statusDormant != null ? statusDormant : DEFAULT_STATUS_DORMANT;
        this.statusDefault = statusDefault != null ? statusDefault : DEFAULT_STATUS_EMPTY;
        this.dormantPeriod = dormantPeriod != null ? dormantPeriod : DEFAULT_DORMANT_PERIOD;
        this.pollingPeriod = pollingPeriod != null ? pollingPeriod : DEFAULT_POLLING_PERIOD;
        this.epoch = epoch != null ? epoch : Instant.now();

        log.debug("Dormant user settings:\n" +
                        "\t{} dormant period,\n" +
                        "\t{} polling period,\n" +
                        "\t\"{}\" dormant status,\n" +
                        "\t\"{}\" default status,\n" +
                        "\t{} epoch",
                 this.dormantPeriod, this.pollingPeriod,
                 this.statusDormant, this.statusDefault, this.epoch);
    }

    private Instant getOrNow(EditablePluginConfig config, String name, boolean update) {
        Instant instant = config.getInstant(name);
        if (instant != null) {
            return instant;
        }
        Instant now = Instant.now();
        if (update) {
            config.setString(name, now.toString());
        }
        return now;
    }

    public String getDormantUserStatus() {
        return statusDormant;
    }

    public String getDefaultUserStatus() {
        return statusDefault;
    }

    public TemporalAmount getDormantPeriod() {
        return dormantPeriod;
    }

    public TemporalAmount getPollingPeriod() {
        return pollingPeriod;
    }

    public Instant getEpoch() {
        return epoch;
    }

    final private static class ListeningFileBasedConfig extends FileBasedConfig {
        public ListeningFileBasedConfig(File cfgLocation, FS fs) {
            super(cfgLocation, fs);
        }

        @Override
        protected boolean notifyUponTransientChanges() {
            return true;
        }
    }

    final private static class EditablePluginConfig extends PluginConfig implements AutoCloseable {
        private final Logger log = LoggerFactory.getLogger(EditablePluginConfig.class);

        final StoredConfig config;
        final AtomicBoolean dirty = new AtomicBoolean(false);

        private EditablePluginConfig(String pluginName, StoredConfig config) {
            super(pluginName, config);
            this.config = config;
            config.addChangeListener(configChangedEvent -> dirty.set(true));
        }

        public static EditablePluginConfig fromFile(String pluginName, File configFile)
                throws IOException, ConfigInvalidException {
            FileBasedConfig cfg = new ListeningFileBasedConfig(configFile, FS.DETECTED);
            cfg.load();
            return new EditablePluginConfig(pluginName, cfg);
        }

        @Override
        public void close() throws IOException {
            if (dirty.get()) {
                config.save();
            }
        }

        public Duration getDuration(String name) {
            String string = getString(name);
            if (string == null || string.isEmpty()) {
                return null;
            }
            Duration duration = null;
            try {
                duration = Duration.parse(string);
            } catch (DateTimeParseException e) {
                log.error("Failed to parse duration for {} section: {} (use ISO 8601 duration format)",
                          name, string, e);
            }
            return duration;
        }

        public Instant getInstant(String name) {
            String string = getString(name);
            if (string == null || string.isEmpty()) {
                return null;
            }
            Instant instant = null;
            try {
                instant = Instant.parse(string);
            } catch (DateTimeParseException e) {
                log.error("Failed to parse date for {} section: {}", name, string, e);
            }
            return instant;
        }
    }
}
