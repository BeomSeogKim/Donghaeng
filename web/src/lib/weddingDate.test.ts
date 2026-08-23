import { describe, expect, it } from 'vitest'
import { daysUntil, formatWeddingDate } from './weddingDate'

describe('formatWeddingDate', () => {
  it('spells the date the way the running head does', () => {
    expect(formatWeddingDate('2026-10-17')).toBe('2026. 10. 17. (토)')
  })

  it('hands back what it was given when the date cannot be read', () => {
    // The API's contract is `YYYY-MM-DD`. If it ever is not, the running head
    // shows the raw value rather than `Invalid Date`.
    expect(formatWeddingDate('nonsense')).toBe('nonsense')
  })
})

describe('daysUntil', () => {
  /** A moment, as an instant, so the Seoul boundary is what is being tested. */
  const at = (iso: string) => new Date(iso)

  it('counts down', () => {
    expect(daysUntil('2026-10-17', at('2026-08-23T09:00:00+09:00'))).toBe('D-55')
  })

  it('says D-DAY on the day itself', () => {
    expect(daysUntil('2026-10-17', at('2026-10-17T06:00:00+09:00'))).toBe('D-DAY')
  })

  it('counts up afterwards — a ledger built after the fact is a real case', () => {
    expect(daysUntil('2026-10-17', at('2026-10-20T09:00:00+09:00'))).toBe('D+3')
  })

  /*
   * THE BOUNDARY IS SEOUL'S MIDNIGHT AND NOBODY ELSE'S. Both instants below are
   * the same moment; one is written in KST and one in UTC. A device in London
   * would call it 16 October and a device in Seoul 17 October, and the couple
   * must not be shown two different countdowns for their one wedding.
   */
  it('turns over at Seoul midnight, not the device clock', () => {
    expect(daysUntil('2026-10-17', at('2026-10-17T00:30:00+09:00'))).toBe('D-DAY')
    expect(daysUntil('2026-10-17', at('2026-10-16T15:30:00Z'))).toBe('D-DAY')
    expect(daysUntil('2026-10-17', at('2026-10-16T14:30:00Z'))).toBe('D-1')
  })

  it('has no answer for a date it cannot read', () => {
    expect(daysUntil('', at('2026-08-23T09:00:00+09:00'))).toBeNull()
  })
})
