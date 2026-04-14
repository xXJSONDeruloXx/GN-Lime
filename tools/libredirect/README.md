# libredirect — build from source

Replaces the binary-patching approach in `tools/lime-asset-patcher/` with
source-compiled `libredirect.so` and `libredirect-bionic.so` libraries.

## What these libraries do

Both are `LD_PRELOAD` shims that intercept filesystem calls and rewrite paths
at runtime so Wine/Box64 (which were built referencing Winlator's package paths)
can find files under the current package's data directory.

| Library | Layer | Compiler | Hooks | Rewrite |
|---------|-------|----------|-------|---------|
| `libredirect.so` | GLIBC imagefs | `aarch64-linux-gnu-gcc` | ~60 functions | `com.winlator/files/rootfs` → `app.gnlime/files/imagefs` |
| `libredirect-bionic.so` | Android bionic | Android NDK clang | 6 functions | `com.winlator.cmod` → `app.gnlime` |

## Building

### GLIBC version (cross-compile)

```bash
# With local cross-compiler
sudo apt install gcc-aarch64-linux-gnu
make glibc

# Or via Docker (no local toolchain needed)
make docker-build
```

### Bionic version (Android NDK)

```bash
export NDK_HOME=/path/to/android-ndk
make bionic
```

### Both at once

```bash
make
```

### Custom package IDs

```bash
make OLD_SUB=com.winlator NEW_SUB=app.myfork
```

Output goes to `out/`.

## Comparing against existing binaries

After building, compare the symbols and strings to validate equivalence:

```bash
# Symbol comparison
nm -D out/libredirect-bionic.so | sort > /tmp/new_syms.txt
nm -D app/src/main/assets/redirect_extracted/usr/lib/libredirect-bionic.so | sort > /tmp/old_syms.txt
diff /tmp/old_syms.txt /tmp/new_syms.txt

# String comparison
strings out/libredirect.so | sort > /tmp/new_str.txt
strings existing/libredirect.so | sort > /tmp/old_str.txt
diff /tmp/old_str.txt /tmp/new_str.txt
```

Exact byte-for-byte matches are unlikely (different compiler versions, 
optimization choices), but the **exported symbols and runtime behavior**
should be identical.

## Integration

Once validated, replace the binary patching workflow:

1. Build both `.so` files with the target `NEW_SUB`
2. Pack into `redirect.tzst`:
   ```bash
   mkdir -p usr/lib
   cp out/libredirect.so out/libredirect-bionic.so usr/lib/
   tar -cf - usr/ | zstd -o redirect.tzst
   ```
3. Replace `app/src/main/assets/redirect.tzst`
4. Remove `tools/lime-asset-patcher/` (no longer needed for redirect)

Note: the asset patcher is still needed for `box64` PT_INTERP and
graphics driver ICD JSON files — only the redirect shim is replaced
by building from source.
