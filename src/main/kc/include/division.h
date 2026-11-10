/// @file
/// Simple binary division implementation
///
/// Follows the C99 standard by truncating toward zero on negative results.
/// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5

/// Remainder after signed 8 bit division
extern char rem8u;

/// Performs division on two 8 bit unsigned chars
/// Returns dividend/divisor.
/// The remainder will be set into the global variable rem8u
/// Implemented using simple binary division
char div8u(char dividend, char divisor);

/// Performs division on two 8 bit unsigned chars and an initial remainder
/// Returns dividend/divisor.
/// The final remainder will be set into the global variable rem8u
/// Implemented using simple binary division
char divr8u(char dividend, char divisor, char rem);

/// Divide unsigned 16-bit unsigned long dividend with a 8-bit unsigned char divisor
/// The 8-bit unsigned char remainder can be found in rem8u after the division
unsigned int div16u8u(unsigned int dividend, unsigned char divisor);

/// Remainder after unsigned 16-bit division
extern unsigned int rem16u;

/// Performs division on two 16 bit unsigned ints and an initial remainder
/// Returns the quotient dividend/divisor.
/// The final remainder will be set into the global variable rem16u
/// Implemented using simple binary division
unsigned int divr16u(unsigned int dividend, unsigned int divisor, unsigned int rem);

/// Performs modulo on two 16 bit unsigned ints and an initial remainder
/// Returns the remainder.
/// Implemented using simple binary division
unsigned int modr16u(unsigned int dividend, unsigned int divisor, unsigned int rem);

/// Performs division on two 16 bit unsigned ints
/// Returns the quotient dividend/divisor.
/// The remainder will be set into the global variable rem16u
/// Implemented using simple binary division
unsigned int div16u(unsigned int dividend, unsigned int divisor);

/// Divide unsigned 32-bit unsigned long dividend with a 16-bit unsigned int divisor
/// The 16-bit unsigned int remainder can be found in rem16u after the division
unsigned long div32u16u(unsigned long dividend, unsigned int divisor);

/// Remainder after signed 8 bit division
extern signed char rem8s;

/// Perform division on two signed 8-bit numbers
/// Returns dividend/divisor.
/// The remainder will be set into the global variable rem8s.
/// Implemented using simple binary division
/// Follows the C99 standard by truncating toward zero on negative results.
/// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
signed char div8s(signed char dividend, signed char divisor);

/// Remainder after signed 16 bit division
extern int rem16s;

/// Perform division on two signed 16-bit numbers with an initial remainder.
/// Returns dividend/divisor. The remainder will be set into the global variable rem16s.
/// Implemented using simple binary division
/// Follows the C99 standard by truncating toward zero on negative results.
/// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
int divr16s(int dividend, int divisor, int rem);

/// Perform division on two signed 16-bit numbers
/// Returns dividend/divisor.
/// The remainder will be set into the global variable rem16s.
/// Implemented using simple binary division
/// Follows the C99 standard by truncating toward zero on negative results.
/// See http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf section 6.5.5
int div16s(int dividend, int divisor);


/// High-speed division by 10 for unsigned 8-bit integers.
/// Uses reciprocal constant multiplication in ~22 CPU cycles.
char div10u(char val);

/// High-speed modulo by 10 for unsigned 8-bit integers.
/// Uses reciprocal multiplication in ~26 CPU cycles.
char mod10u(char val);


/// Performs modulo on two 8 bit unsigned chars
/// Returns dividend % divisor.
char mod8u(char dividend, char divisor);

/// Performs modulo on two 8 bit signed chars
/// Returns dividend % divisor.
signed char mod8s(signed char dividend, signed char divisor);

/// Performs modulo on two 16 bit unsigned ints
/// Returns dividend % divisor.
unsigned int mod16u(unsigned int dividend, unsigned int divisor);

/// Performs modulo on two 16 bit signed ints
/// Returns dividend % divisor.
signed int mod16s(signed int dividend, signed int divisor);


/// Remainder after unsigned 32-bit division
extern unsigned long rem32u;

/// Performs division on two 32 bit unsigned longs
/// Returns dividend / divisor. The remainder will be set into rem32u.
unsigned long div32u(unsigned long dividend, unsigned long divisor);

/// Performs modulo on two 32 bit unsigned longs
/// Returns dividend % divisor.
unsigned long mod32u(unsigned long dividend, unsigned long divisor);

/// Performs division on two 32 bit signed longs
/// Returns dividend / divisor.
signed long div32s(signed long dividend, signed long divisor);

/// Performs modulo on two 32 bit signed longs
/// Returns dividend % divisor.
signed long mod32s(signed long dividend, signed long divisor);

/// Performs division on two 24 bit unsigned tribytes
/// Returns dividend / divisor.
__uint24 div24u(__uint24 dividend, __uint24 divisor);

/// Performs modulo on two 24 bit unsigned tribytes
/// Returns dividend % divisor.
__uint24 mod24u(__uint24 dividend, __uint24 divisor);

/// Performs division on two 24 bit signed tribytes
/// Returns dividend / divisor.
__int24 div24s(__int24 dividend, __int24 divisor);

/// Performs modulo on two 24 bit signed tribytes
/// Returns dividend % divisor.
__int24 mod24s(__int24 dividend, __int24 divisor);
