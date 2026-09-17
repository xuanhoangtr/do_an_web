package com.utewedding.app.domain;

import org.hibernate.Hibernate;

/** Shared JPA-safe identity rules for the migrated entity model. */
public final class EntityIdentity {

    private EntityIdentity() {
    }

    public static boolean same(Object self, Object other, Object id, Object otherId) {
        return self == other || (other != null
                && Hibernate.getClass(self) == Hibernate.getClass(other)
                && id != null
                && id.equals(otherId));
    }

    public static int hashCode(Object entity) {
        return Hibernate.getClass(entity).hashCode();
    }
}
