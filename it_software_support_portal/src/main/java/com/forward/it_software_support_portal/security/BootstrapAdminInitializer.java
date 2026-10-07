package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates one administrator on first start, so a freshly migrated database is actually usable.
 *
 * <p>Without this there is a deadlock: creating a user requires {@code USER_MANAGE}, which requires an
 * account, which requires someone to create it. Enabling authentication on a database whose users all
 * have a {@code NULL} password hash would otherwise lock everyone out permanently.
 *
 * <p>Three guards keep this from becoming a backdoor:
 *
 * <ul>
 *   <li><strong>Opt-in.</strong> Nothing happens unless both email and password are configured. There
 *       is no built-in default account and no default password.
 *   <li><strong>Once only.</strong> It runs only when no user holds the {@code ADMIN} role. It will not
 *       recreate, reset or re-enable an administrator that already exists, so it cannot be used to
 *       overwrite a password by restarting with different configuration.
 *   <li><strong>Never logs the password.</strong> Only the email and id are logged.
 * </ul>
 *
 * <p>Configure it through the environment, not a committed file - see docs/SECURITY.md.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     SecurityProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityProperties.BootstrapAdmin config = properties.bootstrapAdmin();

        if (!config.isConfigured()) {
            if (userRepository.countByRole(Role.ADMIN) == 0) {
                log.warn("""
                        No administrator account exists and app.security.bootstrap-admin is not \
                        configured, so none was created. Nobody can manage users or applications until \
                        an administrator exists. Set app.security.bootstrap-admin.email and \
                        .password (for example from the environment) and restart.""");
            }
            return;
        }

        if (userRepository.countByRole(Role.ADMIN) > 0) {
            log.info("An administrator already exists; bootstrap administrator not created");
            return;
        }

        if (userRepository.findByEmail(config.email()).isPresent()) {
            log.warn("Bootstrap administrator not created: a user already exists with email {}",
                    config.email());
            return;
        }

        User admin = new User();
        admin.setEmail(config.email());
        admin.setFullName(config.fullNameOrDefault());
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        // employee_code is NOT NULL and UNIQUE but carries no meaning for a bootstrap account.
        admin.setEmployeeCode(nextFreeEmployeeCode());
        admin.setPasswordHash(passwordEncoder.encode(config.password()));

        User saved = userRepository.save(admin);
        log.info("Created bootstrap administrator id={} email={}. Change this password after first "
                        + "sign-in via PATCH /api/users/me/password, then remove the configured value.",
                saved.getId(), saved.getEmail());
    }

    private long nextFreeEmployeeCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            long candidate = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
            if (userRepository.findByEmployeeCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate an employee code for the bootstrap admin");
    }
}
