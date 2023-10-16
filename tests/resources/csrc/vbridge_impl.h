#pragma once

#include <optional>
#include <queue>

#include "verilated_fst_c.h"
#include <VTestBench__Dpi.h>

#include <svdpi.h>

#include "util.h"

#include <cassert>
#include <cstdint>
#include <cstdio>

extern "C" {
#include "functions.h"
#include "genCases.h"
#include "genLoops.h"
#include "softfloat.h"
}

struct DutInterface {
  svBit *valid;
  svBitVecVal *a;
  svBitVecVal *b;
  svBit *op;
  svBitVecVal *rm;
};

struct testdata {
  uint64_t a;
  uint64_t b;
  uint64_t expected_out;
  function_t function;
  exceptionFlag_t expectedException;
};

class VBridgeImpl {
public:
  explicit VBridgeImpl();

  void dpiDumpWave();

  void dpiInitCosim();

  uint64_t get_t();

  int timeoutCheck();

  uint64_t getCycle() { return ctx->time(); }

  void dpiPoke(const DutInterface &toDut);

  void dpiPeek(svBit ready);

  std::queue<testdata> test_queue;

  testdata testcase;

  void initTestCases();

  void dpiCheck(svBit valid, svBitVecVal result, svBitVecVal fflags);

  void set_available();

  void clr_available();

  void reloadcase();

  uint64_t cnt;

  roundingMode_t roundingMode;

  bool opSignal;

  std::string rmstring;

private:
  VerilatedContext *ctx;
  VerilatedFstC tfp;

  uint64_t _cycles;

  bool terminate;

  bool available;

  const std::string wave = get_env_arg("wave");

  const std::string op = get_env_arg("op");

  const int rm = std::stoul(get_env_arg("rm"), nullptr, 10);
};

extern VBridgeImpl vbridge_impl_instance;
