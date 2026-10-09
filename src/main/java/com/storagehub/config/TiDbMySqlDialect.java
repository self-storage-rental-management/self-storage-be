package com.storagehub.config;

import org.hibernate.dialect.MySQLDialect;

/**
 * TiDB accepts MySQL locking syntax but does not accept MySQL's
 * {@code FOR UPDATE OF <alias>} form. Keep row-level pessimistic locking and
 * let Hibernate render the portable {@code FOR UPDATE} clause instead.
 */
public class TiDbMySqlDialect extends MySQLDialect {

    @Override
    protected boolean supportsAliasLocks() {
        return false;
    }
}
