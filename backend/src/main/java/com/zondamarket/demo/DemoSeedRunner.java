package com.zondamarket.demo;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "zonda.demo.seed.enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {
    private static final Profiles ALLOWED_PROFILES = Profiles.of("(local | demo) & !prod & !production");
    private final JdbcClient sql;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Environment environment;
    private final PasswordEncoder encoder;
    private final String participantPassword;
    private final String adminPassword;

    DemoSeedRunner(JdbcClient sql, JdbcTemplate jdbc, Clock clock, Environment environment, PasswordEncoder encoder,
            @Value("${zonda.demo.seed.password:}") String participantPassword,
            @Value("${zonda.demo.seed.admin-password:}") String adminPassword) {
        this.sql = sql;
        this.jdbc = jdbc;
        this.clock = clock;
        this.environment = environment;
        this.encoder = encoder;
        this.participantPassword = participantPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!environment.acceptsProfiles(ALLOWED_PROFILES)) {
            throw new IllegalStateException("La carga de demostración está habilitada solo en local o demo.");
        }
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (sql.sql("""
                INSERT INTO zonda_market.demo_seed_run (version, seeded_at) VALUES (1, :now)
                ON CONFLICT (version) DO NOTHING
                """).param("now", now).update() == 0) {
            return;
        }
        if (!valid(participantPassword) || !valid(adminPassword) || participantPassword.equals(adminPassword)) {
            throw new IllegalStateException("Configurá contraseñas de demo distintas de 12 a 72 bytes para participantes y administración.");
        }
        sql.sql("""
                CREATE TEMP TABLE demo_context (seeded_at TIMESTAMPTZ, participant_hash TEXT, admin_hash TEXT) ON COMMIT DROP
                """).update();
        sql.sql("INSERT INTO demo_context VALUES (:now, :participant, :admin)")
                .param("now", now).param("participant", encoder.encode(participantPassword))
                .param("admin", encoder.encode(adminPassword)).update();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            for (String file : new String[] {"catalog.sql", "history.sql"}) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ClassPathResource("demo/" + file), StandardCharsets.UTF_8));
            }
            return null;
        });
        if (sql.sql("SELECT count(*) FROM demo_listings").query(Integer.class).single() != 80
                || sql.sql("SELECT count(*) FROM demo_orders").query(Integer.class).single() != 3) {
            throw new IllegalStateException("La carga de demostración quedó incompleta. Revisá las categorías base.");
        }
    }

    private static boolean valid(String value) {
        return !value.isBlank() && value.length() >= 12 && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
