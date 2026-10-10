#include <stdio.h>

#define STR_(x) #x
#define STR(x) STR_(x)

int main(void)
{
    printf("%s\n", GREETING);
#ifdef BUILD_TIME
    printf("built at %s\n", STR(BUILD_TIME));
#endif
    return 0;
}
