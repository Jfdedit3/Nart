import { useEffect, useRef } from "react";
import { registerNativeBack } from "../lib/nativeBack";

export function useNativeBack(enabled, handler, priority = 100) {
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => enabled ? registerNativeBack(() => latest.current(), priority) : undefined, [enabled, priority]);
}
