{ stdenv, fetchFromGitHub }:
stdenv.mkDerivation rec {
  pname = "softfloat";
  version = "5c06db33fc1e2130f67c045327b0ec949032df1d";
  src = fetchFromGitHub {
    owner = "ucb-bar";
    repo = "berkeley-softfloat-3";
    rev = version;
    sha256 = "sha256-uqf2xATeLyPEs/f8Yqc/Cr5YiklV2754g8IJu5z50sk=";
  };
  patches = [ ./softfloat.patch ];
  buildPhase = ''
    make -C build/Linux-x86_64-GCC SPECIALIZE_TYPE=RISCV TESTFLOAT_OPTS="-DFLOAT64 -DFLOAT_ROUND_ODD" softfloat.a
  '';
  installPhase = ''
    mkdir -p $out/lib
    mkdir -p $out/include
    mv build/Linux-x86_64-GCC/softfloat.a $out/lib/
    cp source/include/* $out/include
  '';
}
