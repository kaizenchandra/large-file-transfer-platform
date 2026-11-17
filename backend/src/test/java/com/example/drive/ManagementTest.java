package com.example.drive;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ManagementTest {
 @Test void forgedOrdinaryAuthenticationCannotReadAdminData(){var api=new ManagementApi(null);var user=new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("ROLE_USER")));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->api.adminTenant(user));}
}
