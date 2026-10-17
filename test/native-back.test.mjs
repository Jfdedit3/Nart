import { test } from "node:test";
import assert from "node:assert/strict";
import { handleNativeBack, registerNativeBack } from "../src/lib/nativeBack.js";

test("back closes the newest overlay before leaving the player", () => {
  const calls = [];
  const removePlayer = registerNativeBack(() => { calls.push("player"); return true; }, 10);
  const removeDialog = registerNativeBack(() => { calls.push("dialog"); return true; });
  const removeSheet = registerNativeBack(() => { calls.push("sheet"); return true; });
  assert.equal(handleNativeBack(), true);
  assert.deepEqual(calls, ["sheet"]);
  removeSheet(); handleNativeBack();
  assert.deepEqual(calls, ["sheet", "dialog"]);
  removeDialog(); handleNativeBack();
  assert.deepEqual(calls, ["sheet", "dialog", "player"]);
  removePlayer(); assert.equal(handleNativeBack(), false);
});
test("a closed player menu lets navigation continue", () => {
  const dispose = registerNativeBack(() => false, 10);
  assert.equal(handleNativeBack(), false);
  dispose(); dispose();
});
