package com.storagehub.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        addAuthorities(jwt.getClaim("roles"), "ROLE_", authorities);
        addAuthorities(jwt.getClaim("permissions"), "PERM_", authorities);
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private void addAuthorities(Object rawValues, String prefix, List<GrantedAuthority> authorities) {
        if (rawValues instanceof Collection<?> values) {
            values.stream()
                .map(String::valueOf)
                .map(value -> (GrantedAuthority) new SimpleGrantedAuthority(prefix + value))
                .forEach(authorities::add);
        }
    }
}
