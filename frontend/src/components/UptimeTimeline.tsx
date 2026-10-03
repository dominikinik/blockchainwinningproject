import type { UptimePoint } from '../services/uptime/uptimeService'

const MAX_SEGMENTS = 60

export function UptimeTimeline({ points }: { points: UptimePoint[] }) {
  if (!points.length) return <p className="uptime-empty">No recorded seconds are available yet.</p>

  const groupSize = Math.max(1, Math.ceil(points.length / MAX_SEGMENTS))
  const groups = Array.from({ length: Math.ceil(points.length / groupSize) }, (_, index) =>
    points.slice(index * groupSize, (index + 1) * groupSize))
  const downSeconds = points.filter((point) => point.down).length
  const firstTime = new Date(points[0].time)
  const lastTime = new Date(points[points.length - 1].time)
  const formatTime = (time: Date) => new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(time)

  return <div className="live-timeline-wrap">
    <div className="live-timeline" style={{ gridTemplateColumns: `repeat(${groups.length}, minmax(0, 1fr))` }} role="img" aria-label={`${points.length - downSeconds} up seconds and ${downSeconds} down seconds from ${formatTime(firstTime)} to ${formatTime(lastTime)}`}>
      {groups.map((group, index) => {
        const down = group.some((point) => point.down)
        return <span key={index} className={`live-timeline-block ${down ? 'down' : 'up'}`} title={`${formatTime(new Date(group[0].time))}–${formatTime(new Date(group[group.length - 1].time))}: ${down ? 'DOWN detected' : 'UP throughout'}`} />
      })}
    </div>
    <div className="live-timeline-axis"><span>{formatTime(firstTime)}</span><span>{groupSize}s per block</span><span>{formatTime(lastTime)}</span></div>
  </div>
}
