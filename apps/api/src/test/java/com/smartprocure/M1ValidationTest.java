package com.smartprocure;
import com.smartprocure.common.M1Support;
import com.smartprocure.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class M1ValidationTest {
    @ParameterizedTest @CsvSource({"0,0","101,0","20,-1","20,10001"})
    void unboundedOrNegativePaginationIsRejected(int limit,int offset) { assertThatThrownBy(()->M1Support.page(limit,offset)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(400)); }
    @Test void validBoundaryPaginationIsAccepted() { assertThatCode(()->{M1Support.page(1,0);M1Support.page(100,10000);}).doesNotThrowAnyException(); }
}
