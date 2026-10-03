export const durationOptions = [
  { value: 30 / 86_400, label: '30 seconds' },
  { value: 60 / 86_400, label: '1 minute' },
  { value: 5 * 60 / 86_400, label: '5 minutes' },
  { value: 15 * 60 / 86_400, label: '15 minutes' },
  { value: 1 / 24, label: '1 hour' },
  { value: 1, label: '1 day' },
  { value: 7, label: '7 days' },
  { value: 14, label: '14 days' },
  { value: 30, label: '30 days' },
] as const

export function formatDuration(days: number): string {
  const seconds = Math.round(days * 86_400)
  if (seconds < 60) return `${seconds} seconds`
  if (seconds < 3600) return `${seconds / 60} ${seconds === 60 ? 'minute' : 'minutes'}`
  if (seconds < 86_400) return `${seconds / 3600} ${seconds === 3600 ? 'hour' : 'hours'}`
  return `${seconds / 86_400} ${seconds === 86_400 ? 'day' : 'days'}`
}
