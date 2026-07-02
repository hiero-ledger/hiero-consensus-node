using Hiero.Proto.Services;

namespace System
{
    public static class DurationExtensions
    {
        public static NodaTime.Duration ToNodaDuration(this Duration timeSpan)
        {
            return NodaTime.Duration.FromSeconds(timeSpan.Seconds);
        }
        public static TimeSpan ToTimeSpan(this Duration duration)
        {
            return TimeSpan.FromSeconds(duration.Seconds);
        }

        public static Duration ToProtoDuration(this TimeSpan timeSpan)
        {
            return new Duration { Seconds = (long)timeSpan.TotalSeconds };
        }
        public static Duration ToProtoDuration(this NodaTime.Duration duration)
        {
            return new Duration {  Seconds = (long)duration.TotalSeconds };
        }
    }
}
