package com.enterprise.iam.core.shared.api.security;

import java.util.Optional;

/** Resolves the actor of the current request. Implemented by the identity module. */
public interface CurrentActorProvider {

    /** @return the actor, or empty for unauthenticated requests */
    Optional<CurrentActor> current();

    /** @throws com.enterprise.iam.kernel.IamException AUTHENTICATION_REQUIRED if unauthenticated */
    CurrentActor require();
}
