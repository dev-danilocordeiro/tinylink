-- Checks and increments both windows in one atomic step, so concurrent requests can't
-- read the same count and all slip under the limit.
-- KEYS: minute counter, hour counter
-- ARGV: minute limit, hour limit, minute window ms, hour window ms
-- Returns {allowed (1/0), remaining requests, ms until the blocking window resets}
local minuteLimit = tonumber(ARGV[1])
local hourLimit = tonumber(ARGV[2])
local minuteCount = tonumber(redis.call('GET', KEYS[1]) or '0')
local hourCount = tonumber(redis.call('GET', KEYS[2]) or '0')

if minuteCount >= minuteLimit then
    return {0, 0, redis.call('PTTL', KEYS[1])}
end
if hourCount >= hourLimit then
    return {0, 0, redis.call('PTTL', KEYS[2])}
end

minuteCount = redis.call('INCR', KEYS[1])
if minuteCount == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
end
hourCount = redis.call('INCR', KEYS[2])
if hourCount == 1 then
    redis.call('PEXPIRE', KEYS[2], ARGV[4])
end

return {1, math.min(minuteLimit - minuteCount, hourLimit - hourCount), 0}
