#include <wolfssl/openssl/ssl.h>
#include <wolfssl/openssl/evp.h>
#include <wolfssl/openssl/x509_vfy.h>
#include <wolfssl/openssl/err.h>
#include <wolfssl/openssl/rand.h>
#include <wolfssl/openssl/bn.h>
#include <wolfssl/openssl/hmac.h>
#include <stdio.h>

/* Compile-time probe: do the TDLib-required OpenSSL symbols exist in wolfSSL? */
int main(void) {
  printf("OPENSSL_VERSION_NUMBER = 0x%lx\n", (unsigned long)OPENSSL_VERSION_NUMBER);
  printf("wolfSSL version = %s\n", wolfSSL_lib_version());
  SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
  SSL_CTX_set_min_proto_version(ctx, TLS1_VERSION);
  SSL_CTX_set_cipher_list(ctx, "DEFAULT");
  SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);
  X509_STORE *store = X509_STORE_new();
  SSL_CTX_set_cert_store(ctx, store);
  EVP_PKEY *p = EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, NULL, NULL, 32);
  EVP_MD_CTX *md = EVP_MD_CTX_new();
  EVP_DigestInit_ex(md, EVP_sha256(), NULL);
  unsigned char buf[32];
  RAND_bytes(buf, sizeof buf);
  printf("ctx=%p pkey=%p md=%p\n", (void *)ctx, (void *)p, (void *)md);
  return ctx == NULL;
}
