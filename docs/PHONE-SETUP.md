# One-time phone setup (for installing PocketPad during development)

You only do this once. It lets the laptop install the app onto your phone over USB.

## 1. Turn on Developer Options

1. Open **Settings** on the phone
2. Go to **About phone**
3. Find **Build number** (sometimes under "Software information")
4. Tap **Build number 7 times** — you'll see "You are now a developer!"

## 2. Turn on USB debugging

1. Go back to **Settings** → **System** → **Developer options**
   (on Samsung: Settings → Developer options)
2. Turn ON **USB debugging**

## 3. Plug the phone into the laptop with the USB cable

- The phone will pop up **"Allow USB debugging?"** with the laptop's fingerprint
- Tick **"Always allow from this computer"** and tap **Allow**

That's it. From then on the laptop can install PocketPad builds directly.

## Playing (once the app is installed)

1. On the laptop, start **PocketPad Link** — it prints something like:
   `host=192.168.1.89  tcp=46822  token=d3125b0a`
2. Make sure the phone is on the **same Wi-Fi** as the laptop
   (or connected to the laptop's Mobile Hotspot)
3. Open **PocketPad** on the phone, type the host and the code, tap **Connect**
4. Start the game. The phone is now an Xbox controller.
