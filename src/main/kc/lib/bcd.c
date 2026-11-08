// 6502 Binary Coded Decimal (BCD) Arithmetic and Conversion
#include <bcd.h>

bcd8_t bcd8_add(bcd8_t a, bcd8_t b) {
    bcd8_t res;
    asm {
        sed
        clc
        lda a
        adc b
        sta res
        cld
    }
    return res;
}

bcd8_t bcd8_sub(bcd8_t a, bcd8_t b) {
    bcd8_t res;
    asm {
        sed
        sec
        lda a
        sbc b
        sta res
        cld
    }
    return res;
}

bcd8_t bcd8_inc(bcd8_t a) {
    return bcd8_add(a, 1);
}

bcd8_t bcd8_dec(bcd8_t a) {
    return bcd8_sub(a, 1);
}

bcd16_t bcd16_add(bcd16_t a, bcd16_t b) {
    bcd16_t res;
    asm {
        sed
        clc
        lda a
        adc b
        sta res
        lda a+1
        adc b+1
        sta res+1
        cld
    }
    return res;
}

bcd16_t bcd16_sub(bcd16_t a, bcd16_t b) {
    bcd16_t res;
    asm {
        sed
        sec
        lda a
        sbc b
        sta res
        lda a+1
        sbc b+1
        sta res+1
        cld
    }
    return res;
}

bcd16_t bcd16_inc(bcd16_t a) {
    return bcd16_add(a, 1);
}

bcd16_t bcd16_dec(bcd16_t a) {
    return bcd16_sub(a, 1);
}

bcd32_t bcd32_add(bcd32_t a, bcd32_t b) {
    bcd32_t res;
    asm {
        sed
        clc
        lda a
        adc b
        sta res
        lda a+1
        adc b+1
        sta res+1
        lda a+2
        adc b+2
        sta res+2
        lda a+3
        adc b+3
        sta res+3
        cld
    }
    return res;
}

bcd32_t bcd32_sub(bcd32_t a, bcd32_t b) {
    bcd32_t res;
    asm {
        sed
        sec
        lda a
        sbc b
        sta res
        lda a+1
        sbc b+1
        sta res+1
        lda a+2
        sbc b+2
        sta res+2
        lda a+3
        sbc b+3
        sta res+3
        cld
    }
    return res;
}

void bcd8_to_str(bcd8_t val, char* buf) {
    buf[0] = (char)((val >> 4) + '0');
    buf[1] = (char)((val & 0x0f) + '0');
    buf[2] = 0;
}

void bcd16_to_str(bcd16_t val, char* buf) {
    uint8_t hi = (uint8_t)(val >> 8);
    uint8_t lo = (uint8_t)val;
    buf[0] = (char)((hi >> 4) + '0');
    buf[1] = (char)((hi & 0x0f) + '0');
    buf[2] = (char)((lo >> 4) + '0');
    buf[3] = (char)((lo & 0x0f) + '0');
    buf[4] = 0;
}

void bcd32_to_str(bcd32_t val, char* buf) {
    for(signed char i = 3; i >= 0; i--) {
        uint8_t b = (uint8_t)(val >> ((uint8_t)(i * 8)));
        *buf++ = (char)((b >> 4) + '0');
        *buf++ = (char)((b & 0x0f) + '0');
    }
    *buf = 0;
}

bcd8_t bin_to_bcd8(uint8_t val) {
    uint8_t tens = 0;
    while(val >= 10) {
        tens++;
        val -= 10;
    }
    return (bcd8_t)((tens << 4) | val);
}

uint8_t bcd8_to_bin(bcd8_t bcd) {
    return (uint8_t)(((bcd >> 4) * 10) + (bcd & 0x0f));
}
