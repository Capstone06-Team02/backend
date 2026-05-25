package capstone2.voisk.embedding.type;

import com.pgvector.PGvector;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Hibernate 미지원 타입인 float[] ↔ pgvector 간 변환 처리.
 * 필드에 @Type(FloatArrayType.class) 선언 후 사용.
 */
public class FloatArrayType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        // pgvector는 표준 SQL 타입 없음 → OTHER 지정
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        String raw = rs.getString(position);
        if (raw == null) return null;
        // Postgres 반환 형식이 "[1.0,2.0]" 또는 "{1.0,2.0}" 두 가지라 모두 처리
        raw = raw.trim().replace("[", "").replace("]", "").replace("{", "").replace("}", "");
        String[] parts = raw.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, WrapperOptions options) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            // PGvector로 래핑해야 JDBC 드라이버가 vector 타입으로 인식
            st.setObject(index, new PGvector(value));
        }
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return deepCopy((float[]) cached);
    }
}
