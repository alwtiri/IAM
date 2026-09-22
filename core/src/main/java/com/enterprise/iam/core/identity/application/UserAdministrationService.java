package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.identity.api.IdentityView;
import com.enterprise.iam.core.identity.api.PersonView;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import java.util.regex.Pattern;

/**
 * Creates a user (person + identity, optionally activated) as ONE transaction, so a rejected username or a validation
 * error never leaves an orphan person behind. Authorization, validation and audit are those of the underlying services.
 */
public class UserAdministrationService {

    public record CreateUser(PersonService.PersonData person, IdentityType type, String username, boolean activate) {
    }

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");

    private final PersonService persons;
    private final IdentityService identities;
    private final IdentityStore store;
    private final TransactionRunner tx;

    public UserAdministrationService(PersonService persons, IdentityService identities, IdentityStore store, TransactionRunner tx) {
        this.persons = persons;
        this.identities = identities;
        this.store = store;
        this.tx = tx;
    }

    public IdentityView create(CurrentActor actor, CreateUser cmd) {
        String username = cmd.username() == null ? "" : cmd.username().trim().toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw IamException.validation("username", "INVALID", "2-64 characters: a-z, 0-9, dot, underscore, hyphen; starts with a letter or digit");
        }
        if (cmd.type() == null) {
            throw IamException.validation("type", "REQUIRED", "identity type is required");
        }
        return tx.inTransaction(() -> {
            if (store.usernameExists(username)) {
                throw IamException.alreadyExists("Username " + username);
            }
            PersonView p = persons.create(actor, cmd.person());
            IdentityView i = identities.create(actor, p.id(), cmd.type(), username, null);
            return cmd.activate() ? identities.transition(actor, i.id(), IdentityState.ACTIVE, "created by administrator") : i;
        });
    }
}
