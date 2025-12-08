# PEI Automotive Frontend

An Android automotive application designed for in-vehicle use, featuring real-time vehicle tracking, speed monitoring, and MQTT-based telemetry integration with MapLibre mapping capabilities.

## Overview

This Android application is built specifically for automotive environments (Android Automotive OS). It provides a real-time map interface with vehicle position tracking, speed monitoring, and alert systems. The app communicates with a backend system via MQTT protocol to receive vehicle telemetry data and display it on an interactive map.

## Features

### Core Functionality
- **Real-time Vehicle Tracking**: Display vehicle position on an interactive map with heading indicators
- **Speed Monitoring**: Current speed display with configurable speed limit warnings
- **MQTT Integration**: Real-time telemetry data reception via MQTT protocol
- **Speed Alerts**: Visual and animated alerts when speed limits are exceeded
- **Interactive Map**: MapLibre-based mapping with custom styling and camera controls
- **Weather Information**: Temperature and weather condition display
- **Route Information**: ETA and distance display for navigation

### User Interface
- Full-screen map view optimized for automotive displays
- Right-side information panel with essential driving data
- Animated speed alert system with visual indicators
- Clean, automotive-focused design suitable for in-vehicle use

## Requirements

### System Requirements
- Android SDK 24 (Android 7.0) or higher
- Target SDK: Android 14 (API 36)
- Android Automotive OS compatible device or emulator (we personally use Kaffa Vim3 Pro)
- Internet connectivity for map tiles and MQTT communication

## Installation

### Prerequisites
1. Install Android Studio (Arctic Fox or newer)
2. Configure Android SDK with API level 36
3. Set up an Android Automotive OS emulator or physical device

### Build Steps

1. Clone the repository:
```bash
git clone https://github.com/ATNoG/pei-automotive-frontend.git
cd pei-automotive-frontend
```

2. Create your configuration file:
```bash
cp local.properties.example local.properties
```

3. Edit `local.properties` and add your API keys and MQTT broker details:
```properties
# MapTiler API Key (get yours at https://cloud.maptiler.com/)
MAPTILER_API_KEY=your_actual_maptiler_api_key_here

# MQTT Broker Configuration
MQTT_BROKER_ADDRESS=192.168.1.201
MQTT_BROKER_PORT=1884
```

4. Open the project in Android Studio

5. Sync Gradle dependencies:
```bash
./gradlew build
```

6. Build and install the application:
```bash
./gradlew installDebug
```

Alternatively, use Android Studio's "Run" button to build and deploy.

## Configuration

All sensitive configuration values are stored in `local.properties` file, which is not committed to version control for security.

### Required Configuration

Create a `local.properties` file in the project root with the following values:

```properties
MAPTILER_API_KEY=your_maptiler_api_key_here
# MQTT Broker Configuration
# Update these with your MQTT broker's address and port
MQTT_BROKER_ADDRESS=192.168.1.201
MQTT_BROKER_PORT=1884
```

### Getting a MapTiler API Key

1. Visit [https://cloud.maptiler.com/](https://cloud.maptiler.com/)
2. Sign up for a free account
3. Navigate to "Account" → "Keys"
4. Copy your API key
5. Paste it into `local.properties` as `MAPTILER_API_KEY`

### MQTT Broker Setup

Update the MQTT broker configuration in `local.properties`:
- `MQTT_BROKER_ADDRESS`: IP address or hostname of your MQTT broker
- `MQTT_BROKER_PORT`: Port number (default is typically 1883 or 1884)

## Usage

### MQTT Topics

The application subscribes to the following MQTT topics:

#### `alerts/speed`
Triggers speed alert notifications.

#### `cars/updates`
Receives vehicle telemetry data in JSON format:
```json
{
  "car_id": "vehicle_001",
  "latitude": 40.6405,
  "longitude": -8.6538,
  "speed_kmh": 75.5,
  "heading_deg": 180.0
}
```

### Running the Application

1. Ensure your MQTT broker is running and accessible
2. Launch the application on an Android Automotive OS device
3. The app will automatically:
   - Connect to the MQTT broker
   - Subscribe to telemetry topics
   - Display the map with vehicle position
   - Show speed and other telemetry data
   - Trigger alerts when thresholds are exceeded

### UI Components

**Map View**
- Displays the current vehicle position with a directional arrow
- Supports zoom and pan gestures
- Auto-centers on vehicle location

**Right Panel Information**
- Current speed (km/h)
- Speed limit indicator
- Temperature and weather conditions
- Distance and ETA to destination

**Speed Alerts**
- Red background overlay when speed exceeds threshold
- Animated warning icon
- Automatically dismisses when speed returns to normal

**Important Files:**
- `local.properties`: Your actual keys (gitignored for security)
- `app/build.gradle.kts`: Reads `local.properties` and exposes values to BuildConfig

## Dependencies

### Core Libraries
- **Kotlin**: 2.0.21
- **AndroidX Core**: 1.17.0
- **AppCompat**: 1.7.1
- **Material Design**: 1.10.0

### Mapping
- **MapLibre Android SDK**: 11.8.5 - Open-source mapping library

### Communication
- **Eclipse Paho MQTT**: 1.2.5 - MQTT client library
- **JSON**: 20231013 - JSON parsing

### Build Tools
- **Android Gradle Plugin**: 8.13.1
- **Gradle**: 8.9

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

**Project**: PEI Automotive Frontend  
**Organization**: ATNoG  
**Platform**: Android Automotive OS  
**Language**: Kotlin