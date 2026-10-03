/** Pure window and slot math from SPEC.md. All times are Unix seconds. */
export interface ScheduleParams {
  startTs: number
  endTs: number
  windowSecs: number
  checkIntervalSecs: number
  totalWindows: number
}

export interface WindowBounds {
  index: number
  start: number
  end: number
  slots: number
}

export function windowBounds(sla: ScheduleParams, index: number): WindowBounds {
  if (!Number.isInteger(index) || index < 0 || index >= sla.totalWindows) {
    throw new RangeError(`window ${index} out of range 0..${sla.totalWindows - 1}`)
  }
  const start = sla.startTs + index * sla.windowSecs
  const end = Math.min(start + sla.windowSecs, sla.endTs)
  return { index, start, end, slots: Math.ceil((end - start) / sla.checkIntervalSecs) }
}

export function slotTime(sla: ScheduleParams, index: number, slot: number): number {
  return windowBounds(sla, index).start + slot * sla.checkIntervalSecs
}

/** The slot due at `now` (the latest slot whose time is <= now), or null outside [start, end). */
export function currentSlot(
  sla: ScheduleParams,
  now: number,
): { window: WindowBounds; slot: number } | null {
  if (now < sla.startTs || now >= sla.endTs) return null
  const index = Math.floor((now - sla.startTs) / sla.windowSecs)
  if (index >= sla.totalWindows) return null
  const window = windowBounds(sla, index)
  return { window, slot: Math.floor((now - window.start) / sla.checkIntervalSecs) }
}
