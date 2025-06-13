#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <PulseSensorPlayground.h>

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

#define BPM_PIN 35

#define LEFT_ECHO 34
#define LEFT_TRIG 25
#define RIGHT_TRIG 14
#define RIGHT_ECHO 14
// Buffer size = number of readings per send (≈10 in 500 ms at 50 ms interval)
#define PROX_SAMPLES 10

float dlBuffer[PROX_SAMPLES];
float drBuffer[PROX_SAMPLES];
int  proxIndex = 0;
bool proxBufferFull = false;

PulseSensorPlayground pulseSensor;
Adafruit_MPU6050 mpu;
BLECharacteristic *pCharacteristic;
BLEServer *pServer;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Shared sensor data
float latestAX, latestAY, latestAZ;
float latestGX, latestGY, latestGZ;
float latestDL, latestDR;
int myBPM = 0;

// Mutex to protect shared data
SemaphoreHandle_t dataMutex;
TaskHandle_t dataTaskHandle = NULL;


class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("BLE client connected");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("BLE client disconnected, restarting advertising...");
    delay(500); // Short delay before advertising again
    BLEDevice::startAdvertising();
  }
};

void triggerSensor(bool left) {
  digitalWrite(left ? LEFT_TRIG : RIGHT_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(left ? LEFT_TRIG : RIGHT_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(left ? LEFT_TRIG : RIGHT_TRIG, LOW);
}

long measureEchoDuration(bool left) {
  return pulseIn(left ? LEFT_ECHO : RIGHT_ECHO, HIGH);
}

float calculateDistanceCM(bool left) {
  triggerSensor(left);
  long duration = measureEchoDuration(left);
  return duration * 0.034 / 2;
}

void dataTask(void * parameter) {
  while (true) {
    // 1) Read MPU (same as before)
    //sensors_event_t a, g, temp;
    //mpu.getEvent(&a, &g, &temp);
    // … store a,g into latestAX, etc under mutex …

    // 2) Read ultrasonics
    float dL = calculateDistanceCM(true);
    float dR = calculateDistanceCM(false);
    
    if (xSemaphoreTake(dataMutex, portMAX_DELAY) == pdTRUE) {
      /*
      latestAX = a.acceleration.x;
      latestAY = a.acceleration.y;
      latestAZ = a.acceleration.z;
      latestGX = g.gyro.x;
      latestGY = g.gyro.y;
      latestGZ = g.gyro.z;
      */
      latestDL = dL;
      latestDR = dR;
      xSemaphoreGive(dataMutex);
    }
    // 3) Store into rolling buffer
    if (xSemaphoreTake(dataMutex, portMAX_DELAY) == pdTRUE) {
      dlBuffer[proxIndex] = dL;
      drBuffer[proxIndex] = dR;
      proxIndex = (proxIndex + 1) % PROX_SAMPLES;
      if (proxIndex == 0) proxBufferFull = true;
      xSemaphoreGive(dataMutex);
    }

    // 4) Wait ~50ms → ≈10 samples in 500ms
    vTaskDelay(100 / portTICK_PERIOD_MS);
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  // MPU6050 başlat
  Wire.begin(14,27);
  /*
  while (!mpu.begin()) {
    Serial.println("MPU6050 bulunamadı!");
    delay(200);
  }
  */
  myBPM = 0;
  Serial.println("MPU6050 başlatıldı.");

  pulseSensor.analogInput(BPM_PIN);

  if (pulseSensor.begin()) {
    Serial.println("Pulse sensor ready.");
  }

  pinMode(LEFT_TRIG, OUTPUT);
  pinMode(RIGHT_TRIG, OUTPUT);
  pinMode(LEFT_ECHO, INPUT);
  pinMode(RIGHT_ECHO, INPUT);
  pinMode(BPM_PIN, INPUT);

  // BLE başlat
  BLEDevice::init("SensorBLE");
  BLEDevice::setMTU(100);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pCharacteristic->addDescriptor(new BLE2902());
  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->start();

  Serial.println("BLE yayın başladı.");
    // Create mutex
  dataMutex = xSemaphoreCreateMutex();

  // Start data collection task on core 1
  xTaskCreatePinnedToCore(
    dataTask,
    "DataTask",
    4096,
    NULL,
    1,
    &dataTaskHandle,
    1
  );
}

void loop() {
  static uint32_t lastSend = 0;
  uint32_t now = millis();

  if (deviceConnected && now - lastSend >= 1000) {
    float ax, ay, az, gx, gy, gz, dl, dr;

    // Safely read the latest values
    if (xSemaphoreTake(dataMutex, portMAX_DELAY) == pdTRUE) {
      ax = latestAX;  ay = latestAY;  az = latestAZ;
      gx = latestGX;  gy = latestGY;  gz = latestGZ;
      dl = latestDL;  dr = latestDR;
      xSemaphoreGive(dataMutex);
    }


    float sumL = 0, sumR = 0;
    int count = proxBufferFull ? PROX_SAMPLES : proxIndex;
    if (count == 0) count = 1;  // avoid div0 on first send
    if (xSemaphoreTake(dataMutex, portMAX_DELAY) == pdTRUE) {
      for (int i = 0; i < count; i++) {
        sumL += dlBuffer[i];
        sumR += drBuffer[i];
      }
      xSemaphoreGive(dataMutex);
    }
    float avgL = sumL / count;
    float avgR = sumR / count;
    if (pulseSensor.sawStartOfBeat()) {
      myBPM = pulseSensor.getBeatsPerMinute();
      Serial.print("BPM: ");
      Serial.println(myBPM);
  } 
    // Build and send the data string
    String allData = "AX:" + String(ax, 2) +
                     " AY:" + String(ay, 2) +
                     " AZ:" + String(az, 2) +
                     " GX:" + String(gx, 2) +
                     " GY:" + String(gy, 2) +
                     " GZ:" + String(gz, 2) +
                     " DL:" + String(avgL, 2) +
                     " DR:" + String(avgR, 2)+
                     "BPM:"+myBPM;

    Serial.println("------ Ölçüm (Main Loop) ------");
    Serial.println(allData);

    pCharacteristic->setValue(allData.c_str());
    pCharacteristic->notify();

    lastSend = now;
  }

  // You can still do other BLE or UI handling here…
  delay(10);
}