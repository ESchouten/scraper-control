// Minimal stand-ins so main.cpp can be syntax-checked on a PC (not the real libraries).
#pragma once
#include <cstddef>
enum mbedtls_md_type_t { MBEDTLS_MD_SHA256 = 9 };
struct mbedtls_pk_context { int x; };
void mbedtls_pk_init(mbedtls_pk_context*); void mbedtls_pk_free(mbedtls_pk_context*);
int mbedtls_pk_parse_public_key(mbedtls_pk_context*, const unsigned char*, size_t);
int mbedtls_pk_verify(mbedtls_pk_context*, mbedtls_md_type_t, const unsigned char*, size_t, const unsigned char*, size_t);
