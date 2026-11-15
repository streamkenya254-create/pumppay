// Default PAYG / PAYU plans and products — used until you add your own in Admin → Plans / Products.
// Same values are seeded into Firestore by Admin → Settings → "Seed default data".
export const DEFAULT_PLANS = [
  {
    id: "payg-starter",
    type: "payg",
    name: "Pay As You Go",
    tagline: "Own your solar pump",
    deposit: 9900,
    dailyRate: 150,          // KES per day of access
    totalPrice: 89900,       // full system price incl. deposit → ownership when reached
    featured: true,
    order: 1,
    features: [
      "Small deposit, then KES 150/day via M-Pesa",
      "Pay daily, weekly or monthly — any amount",
      "System becomes yours after full payment (~18 months)",
      "Free servicing while on plan",
      "Live balance & water data in the app",
    ],
  },
  {
    id: "payu-flex",
    type: "payu",
    name: "Pay As You Use",
    tagline: "Pay only when you pump",
    deposit: 2500,           // installation / refundable security
    hourlyRate: 40,          // KES per pumping hour
    bundles: [
      { hours: 10, price: 400 },
      { hours: 30, price: 1100 },
      { hours: 100, price: 3500 },
    ],
    featured: false,
    order: 2,
    features: [
      "KES 2,500 installation, no long-term contract",
      "KES 40 per pumping hour — buy hours in bundles",
      "Unused hours never expire",
      "Ideal for seasonal & smallholder farming",
      "Upgrade to PAYG ownership any time",
    ],
  },
];

export const DEFAULT_PRODUCTS = [
  { id: "ctrl", name: "PumpPay Smart Controller", price: 24500, emoji: "🎛", desc: "GSM-connected MPPT pump controller with M-Pesa unlock.", active: true },
  { id: "kit-1hp", name: "1HP Solar Pump Kit", price: 89900, emoji: "☀", desc: "Pump, 4× panels, controller and installation. PAYG available.", active: true },
  { id: "drip", name: "Drip Kit — ¼ Acre", price: 18500, emoji: "💧", desc: "Laterals, drippers, filter and fittings for ¼ acre.", active: true },
  { id: "tank", name: "Tank Level Sensor", price: 4500, emoji: "📡", desc: "Auto-stop when your tank is full. Shows level in the app.", active: true },
];
