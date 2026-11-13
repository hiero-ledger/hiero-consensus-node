using Hiero.Proto.Services;

namespace System
{
    public static class TimestampExtensions
    {
        public static DateTimeOffset ToDateTimeOffset(this Timestamp timestamp)
        {
            return DateTimeOffset.UnixEpoch
                .AddSeconds(timestamp.Seconds)
                .AddNanoseconds(timestamp.Nanos);
        }
        public static DateTimeOffset ToDateTimeOffset(this TimestampSeconds timestampSeconds)
        {
            return DateTimeOffset.UnixEpoch
                .AddSeconds(timestampSeconds.Seconds);
        }

        public static NodaTime.Instant ToNodaTimeInstant(this Timestamp timestamp)
        {
            return NodaTime.Instant
                .FromUnixTimeSeconds(timestamp.Seconds)
                .PlusNanoseconds(timestamp.Nanos);
        }
        public static NodaTime.Instant ToNodaTimeInstant(this TimestampSeconds timestampSeconds)
        {
            return NodaTime.Instant
                .FromUnixTimeSeconds(timestampSeconds.Seconds);
        }

        public static Timestamp ToProtoTimestamp(this DateTimeOffset dateTimeOffset)
        {
            return new Timestamp { Seconds = dateTimeOffset.ToUnixTimeSeconds(), Nanos = dateTimeOffset.Nanosecond };
        }
        public static Timestamp ToProtoTimestamp(this NodaTime.Instant instant)
        {
            (long seconds, int nanoseconds) = instant.ToUnixTimeSecondsAndNanoseconds();

            return new Timestamp { Seconds = seconds, Nanos = nanoseconds };
        }
        public static Timestamp ToProtoTimestamp(this NodaTime.Duration instant, NodaTime.Instant? from = null)
        {
            (long seconds, int nanoseconds) = (from ?? NodaTime.SystemClock.Instance.GetCurrentInstant())
                .Plus(instant)
                .ToUnixTimeSecondsAndNanoseconds();

            return new Timestamp { Seconds = seconds, Nanos = nanoseconds };
        }

        public static TimestampSeconds ToProtoTimestampSeconds(this DateTimeOffset dateTimeOffset)
        {
            return new TimestampSeconds { Seconds = dateTimeOffset.ToUnixTimeSeconds() };
        }
        public static TimestampSeconds ToProtoTimestampSeconds(this NodaTime.Instant instant)
        {
            return new TimestampSeconds { Seconds = instant.ToUnixTimeSeconds() };
        }
    }
}
