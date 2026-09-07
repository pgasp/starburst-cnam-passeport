package io.starburst.cnam.passeport;

import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.VarcharType;
import io.trino.spi.type.BooleanType;
import java.util.List;

public final class PasseportFunctions {

    private PasseportFunctions() {}

    @ScalarFunction("passeport_perimetre")
    @Description("Returns the list of caisses (perimetre) for a given Passeport user")
    @SqlType("array(varchar)")
    public static Block getPasseportPerimetre(@SqlNullable @SqlType(StandardTypes.VARCHAR) Slice userSlice) {
        if (userSlice == null) {
            return createEmptyArray();
        }
        
        String user = userSlice.toStringUtf8();
        List<String> perimetres = PasseportPerimetreCache.getInstance().getPerimetre(user);
        
        if (perimetres == null || perimetres.isEmpty()) {
            return createEmptyArray();
        }
        
        BlockBuilder blockBuilder = VarcharType.VARCHAR.createBlockBuilder(null, perimetres.size());
        for (String p : perimetres) {
            VarcharType.VARCHAR.writeSlice(blockBuilder, Slices.utf8Slice(p));
        }
        
        return blockBuilder.build();
    }
    
    @ScalarFunction("flush_passeport_cache")
    @Description("Flushes the Passeport perimetre cache for a specific user (or all if null)")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean flushPasseportCache(@SqlNullable @SqlType(StandardTypes.VARCHAR) Slice userSlice) {
        if (userSlice == null) {
            PasseportPerimetreCache.getInstance().flushAll();
        } else {
            String user = userSlice.toStringUtf8();
            PasseportPerimetreCache.getInstance().flush(user);
        }
        return true;
    }
    
    private static Block createEmptyArray() {
        return VarcharType.VARCHAR.createBlockBuilder(null, 0).build();
    }
}
