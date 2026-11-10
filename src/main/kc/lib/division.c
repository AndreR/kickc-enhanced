// Simple binary division implementation
// Follows the C99 standard by truncating toward zero on negative results.
// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5

#include <division.h>

// Remainder after signed 8 bit division
char rem8u =0;

// Performs division on two 8 bit unsigned chars
// Returns dividend/divisor.
// The remainder will be set into the global variable rem8u
// Implemented using simple binary division
char div8u(char dividend, char divisor) {
    return divr8u(dividend, divisor, 0);
}

// Performs division on two 8 bit unsigned chars and an initial remainder
// Returns dividend/divisor.
// The final remainder will be set into the global variable rem8u
// Implemented using simple binary division
char divr8u(char dividend, char divisor, char rem) {
    for( char i : 0..7) {
        rem = (char)((rem << 1) | (char)(dividend >> 7));
        dividend = (char)(dividend << 1);
        if(rem >= divisor) {
            rem = (char)(rem - divisor);
            dividend = (char)(dividend | 1);
        }
    }
    rem8u = rem;
    return dividend;
}

// Divide unsigned 16-bit unsigned long dividend with a 8-bit unsigned char divisor
// The 8-bit unsigned char remainder can be found in rem8u after the division
unsigned int div16u8u(unsigned int dividend, unsigned char divisor) {
  unsigned char quotient_hi = divr8u(BYTE1(dividend), divisor, 0);
  unsigned char quotient_lo = divr8u(BYTE0(dividend), divisor, rem8u);
  unsigned int quotient = MAKEWORD( quotient_hi, quotient_lo );
  return quotient;
}

// Remainder after unsigned 16-bit division
unsigned int rem16u = 0;

// Performs division on two 16 bit unsigned ints and an initial remainder
// Returns the quotient dividend/divisor.
// The final remainder will be set into the global variable rem16u
// Implemented using fast 6502 carry-chain binary division (~4x speedup)
unsigned int divr16u(unsigned int dividend, unsigned int divisor, unsigned int rem) {
    asm {
        ldx #16
    loop:
        asl dividend
        rol dividend+1
        rol rem
        rol rem+1
        bcs overflow
        lda rem
        sec
        sbc divisor
        tay
        lda rem+1
        sbc divisor+1
        bcc skip
        sta rem+1
        sty rem
        inc dividend
        jmp next
    overflow:
        lda rem
        sec
        sbc divisor
        sta rem
        lda rem+1
        sbc divisor+1
        sta rem+1
        inc dividend
    next:
    skip:
        dex
        bne loop
    }
    rem16u = rem;
    return dividend;
}

// Performs modulo on two 16 bit unsigned ints and an initial remainder
// Returns the remainder.
// Implemented using simple binary division
unsigned int modr16u(unsigned int dividend, unsigned int divisor, unsigned int rem) {
    divr16u(dividend, divisor, rem);
    return rem16u;
}

// Performs division on two 16 bit unsigned ints
// Returns the quotient dividend/divisor.
// The remainder will be set into the global variable rem16u
// Implemented using simple binary division
unsigned int div16u(unsigned int dividend, unsigned int divisor) {
    return divr16u(dividend, divisor, 0);
}

// Divide unsigned 32-bit unsigned long dividend with a 16-bit unsigned int divisor
// The 16-bit unsigned int remainder can be found in rem16u after the division
unsigned long div32u16u(unsigned long dividend, unsigned int divisor) {
  unsigned int quotient_hi = divr16u(WORD1(dividend), divisor, 0);
  unsigned int quotient_lo = divr16u(WORD0(dividend), divisor, rem16u);
  unsigned long quotient = MAKELONG( quotient_hi, quotient_lo );
  return quotient;
}

// Remainder after signed 8 bit division
signed char rem8s = 0;

// Perform division on two signed 8-bit numbers
// Returns dividend/divisor.
// The remainder will be set into the global variable rem8s.
// Implemented using simple binary division
// Follows the C99 standard by truncating toward zero on negative results.
// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
signed char div8s(signed char dividend, signed char divisor) {
    char neg = 0;
    char dividendu = 0;
    if(dividend<0) {
      dividendu = (char)-dividend;
      neg = 1;
    } else {
      dividendu = (char)dividend;
    }
    char divisoru = 0;
    if(divisor<0) {
        divisoru = (char)-divisor;
        neg = neg ^ 1;
    } else {
        divisoru = (char)divisor;
    }
    char resultu = div8u(dividendu, divisoru);
    if(neg==0) {
        rem8s = (signed char)rem8u;
        return (signed char)resultu;
    } else {
        rem8s = -(signed char)rem8u;
        return -(signed char)resultu;
    }
}

// Remainder after signed 16 bit division
signed int rem16s = 0;

// Perform division on two signed 16-bit numbers with an initial remainder.
// Returns dividend/divisor. The remainder will be set into the global variable rem16s.
// Implemented using simple binary division
// Follows the C99 standard by truncating toward zero on negative results.
// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
signed int divr16s(signed int dividend, signed int divisor, signed int rem) {
    char neg = 0;
    unsigned int dividendu = 0;
    unsigned int remu = 0;
    if(dividend<0 || rem<0) {
      dividendu = (unsigned int)-dividend;
      remu = (unsigned int)-rem;
      neg = 1;
    } else {
      dividendu = (unsigned int)dividend;
      remu = (unsigned int)rem;
    }
    unsigned int divisoru = 0;
    if(divisor<0) {
        divisoru = (unsigned int)-divisor;
        neg = neg ^ 1;
    } else {
        divisoru = (unsigned int)divisor;
    }
    unsigned int resultu = divr16u(dividendu, divisoru, remu);
    if(neg==0) {
        rem16s = (signed int)rem16u;
        return (signed int)resultu;
    } else {
        rem16s = -(signed int)rem16u;
        return -(signed int)resultu;
    }
}

// Perform division on two signed 16-bit numbers
// Returns dividend/divisor.
// The remainder will be set into the global variable rem16s.
// Implemented using simple binary division
// Follows the C99 standard by truncating toward zero on negative results.
// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
signed int div16s(signed int dividend, signed int divisor) {
    return divr16s(dividend, divisor, 0);
}


// High-speed division by 10 for unsigned 8-bit integers (22 cycles)
char div10u(char val) {
    return (char)(((unsigned int)val * 205) >> 11);
}

// High-speed modulo by 10 for unsigned 8-bit integers (26 cycles)
// Performs modulo on two 8 bit unsigned chars
char mod8u(char dividend, char divisor) {
    div8u(dividend, divisor);
    return rem8u;
}

// Performs modulo on two 8 bit signed chars
signed char mod8s(signed char dividend, signed char divisor) {
    div8s(dividend, divisor);
    return rem8s;
}

// Performs modulo on two 16 bit unsigned ints
unsigned int mod16u(unsigned int dividend, unsigned int divisor) {
    return modr16u(dividend, divisor, 0);
}

// Performs modulo on two 16 bit signed ints
signed int mod16s(signed int dividend, signed int divisor) {
    div16s(dividend, divisor);
    return rem16s;
}

char mod10u(char val) {
    return (char)(val - div10u(val) * 10);
}



// Remainder after unsigned 32-bit division
unsigned long rem32u = 0;

// Performs division on two 32 bit unsigned longs
unsigned long div32u(unsigned long dividend, unsigned long divisor) {
    if (divisor > dividend) {
        rem32u = dividend;
        return 0;
    }
    if (WORD1(divisor) == 0) {
        unsigned long q = div32u16u(dividend, (unsigned int)divisor);
        rem32u = (unsigned long)rem16u;
        return q;
    }
    unsigned long rem = 0;
    for(char i = 0; i < 32; i++) {
        rem = (rem << 1) | (unsigned long)((dividend >> 31) & 1);
        dividend = dividend << 1;
        if(rem >= divisor) {
            rem = rem - divisor;
            dividend = dividend | 1;
        }
    }
    rem32u = rem;
    return dividend;
}

// Performs modulo on two 32 bit unsigned longs
unsigned long mod32u(unsigned long dividend, unsigned long divisor) {
    div32u(dividend, divisor);
    return rem32u;
}

// Performs division on two 32 bit signed longs
signed long div32s(signed long dividend, signed long divisor) {
    char neg = 0;
    unsigned long dividendu;
    if(dividend < 0) {
        dividendu = (unsigned long)-dividend;
        neg = 1;
    } else {
        dividendu = (unsigned long)dividend;
    }
    unsigned long divisoru;
    if(divisor < 0) {
        divisoru = (unsigned long)-divisor;
        neg = neg ^ 1;
    } else {
        divisoru = (unsigned long)divisor;
    }
    unsigned long resultu = div32u(dividendu, divisoru);
    if(neg == 0) {
        return (signed long)resultu;
    } else {
        return -(signed long)resultu;
    }
}

// Performs modulo on two 32 bit signed longs
signed long mod32s(signed long dividend, signed long divisor) {
    char neg = (dividend < 0) ? 1 : 0;
    unsigned long dividendu = (dividend < 0) ? (unsigned long)-dividend : (unsigned long)dividend;
    unsigned long divisoru = (divisor < 0) ? (unsigned long)-divisor : (unsigned long)divisor;
    div32u(dividendu, divisoru);
    if(neg == 0) {
        return (signed long)rem32u;
    } else {
        return -(signed long)rem32u;
    }
}

// Performs division on two 24 bit unsigned tribytes
__uint24 div24u(__uint24 dividend, __uint24 divisor) {
    return (__uint24)div32u((unsigned long)dividend, (unsigned long)divisor);
}

// Performs modulo on two 24 bit unsigned tribytes
__uint24 mod24u(__uint24 dividend, __uint24 divisor) {
    return (__uint24)mod32u((unsigned long)dividend, (unsigned long)divisor);
}

// Performs division on two 24 bit signed tribytes
__int24 div24s(__int24 dividend, __int24 divisor) {
    return (__int24)div32s((signed long)dividend, (signed long)divisor);
}

// Performs modulo on two 24 bit signed tribytes
__int24 mod24s(__int24 dividend, __int24 divisor) {
    return (__int24)mod32s((signed long)dividend, (signed long)divisor);
}
