package io.starburst.cnam.passeport;

import java.util.List;

import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.VarcharType;

public final class PasseportFunctions {

    private PasseportFunctions() {}

    @ScalarFunction("passeport_perimetre")
    @Description("Returns the list of caisses (perimetre) for the current user")
    @SqlType("array(varchar)")
    public static Block getPasseportPerimetre(ConnectorSession session) {
        if (session == null || session.getUser() == null) {
            return createEmptyArray();
        }
        
        String user = session.getUser();
        PasseportAuthService authService = PasseportAuthService.getGlobalInstance();
        
        if (authService == null) {
            return createEmptyArray();
        }
        
        List<String> perimetres = authService.getPerimetres(user);
        if (perimetres == null || perimetres.isEmpty()) {
            return createEmptyArray();
        }
        
        BlockBuilder blockBuilder = VarcharType.VARCHAR.createBlockBuilder(null, perimetres.size());
        for (String p : perimetres) {
            VarcharType.VARCHAR.writeSlice(blockBuilder, Slices.utf8Slice(p));
        }
        
        return blockBuilder.build();
    }
    
    @ScalarFunction("current_user_biac_roles")
    @Description("Returns the list of enabled system roles (BIAC) for the current user from the session identity")
   @SqlType("array(varchar)")
    public static Block getPassportBiacRoles(ConnectorSession session) {
        if (session == null || session.getIdentity() == null) {
             return createEmptyArray();
        }
        
        java.util.Set<String> roles = session.getIdentity().getEnabledSystemRoles();
        if (roles == null || roles.isEmpty()) {
             return createEmptyArray();
        }
         BlockBuilder blockBuilder = VarcharType.VARCHAR.createBlockBuilder(null, roles.size());
         for (String p : roles) {
            VarcharType.VARCHAR.writeSlice(blockBuilder, Slices.utf8Slice(p));
        }
        return blockBuilder.build();
    }
    

    
    private static Block createEmptyArray() {
        return VarcharType.VARCHAR.createBlockBuilder(null, 0).build();
    }
}
