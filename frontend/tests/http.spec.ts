import { SingleFlight } from '@/api/http'

describe('SingleFlight', () => {
  it('coalesces simultaneous refresh attempts and resets after completion', async () => {
    const flight = new SingleFlight<number>()
    let calls = 0
    const task = async (): Promise<number> => {
      calls += 1
      await Promise.resolve()
      return calls
    }

    const firstBatch = await Promise.all([flight.run(task), flight.run(task), flight.run(task)])
    expect(firstBatch).toEqual([1, 1, 1])
    expect(calls).toBe(1)
    await expect(flight.run(task)).resolves.toBe(2)
  })
})
