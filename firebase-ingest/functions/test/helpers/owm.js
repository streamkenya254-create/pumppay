/**
 * A stand-in for OpenWeather: builds an answer shaped like /data/2.5/forecast (one point every
 * 3 hours for 5 days) so the sun forecast and the weather insights can be tested with weather of
 * our choosing.
 */
const TZ = 3 * 3600;                                             // East Africa Time

/**
 * cloudAt, rainAt, popAt and airAt are each (localHour, dayIndex) => value, dayIndex 0 = today.
 *   cloudAt  cloud cover 0-100            rainAt  mm in the 3 hours up to the point
 *   popAt    chance of rain 0-1           airAt   { temp (C), humidity (%), wind (m/s) }, any of them
 */
function forecast(cloudAt, rainAt = () => 0, popAt = () => 0, now = new Date("2026-11-15T03:00:00Z"), airAt = null) {
  const list = [];
  const first = Math.ceil(now.getTime() / (3 * 3600e3)) * 3 * 3600e3;
  for (let i = 0; i < 40; i++) {
    const t = first + i * 3 * 3600e3;
    const local = new Date(t + TZ * 1000);
    const hour = local.getUTCHours();
    const day = Math.floor((t + TZ * 1000) / 864e5) - Math.floor((now.getTime() + TZ * 1000) / 864e5);
    const e = { dt: t / 1000, clouds: { all: cloudAt(hour, day) }, pop: popAt(hour, day) };
    const mm = rainAt(hour, day);
    if (mm > 0) e.rain = { "3h": mm };
    if (airAt) {
      const a = airAt(hour, day) || {};
      if (a.temp !== undefined || a.humidity !== undefined) e.main = { temp: a.temp, temp_min: a.temp, temp_max: a.temp, humidity: a.humidity };
      if (a.wind !== undefined) e.wind = { speed: a.wind };
    }
    list.push(e);
  }
  return { city: { timezone: TZ }, list };
}

/** A plain warm day with cool nights: 16 C before dawn, 27 C mid-afternoon, light wind, 60% humidity. */
const mildAir = (hour) => ({ temp: 21.5 + 5.5 * Math.cos(((hour - 15) / 24) * 2 * Math.PI), humidity: 60, wind: 2 });

module.exports = { forecast, mildAir, TZ };
