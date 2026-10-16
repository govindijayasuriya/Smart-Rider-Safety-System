import time
import cv2
import numpy as np
from picamera2 import Picamera2
from ultralytics import YOLO

MODEL = "/home/iotrix/yolo/yolo11n_ncnn_model"

# Initialize camera
picam2 = Picamera2()
config = picam2.create_video_configuration(
    main={"size": (640, 480), "format": "RGB888"}
)
picam2.configure(config)
picam2.start()
time.sleep(2)

# Load exported NCNN model
model = YOLO(MODEL, task="detect")

print("Live detection started. Press Q to quit.")

try:
    while True:
        frame = picam2.capture_array()

        # Picamera2 returns RGB; OpenCV display uses BGR
        frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)

        results = model.predict(
            source=frame,
            imgsz=640,
            conf=0.35,
            device="cpu",
            verbose=False
        )

        annotated = results[0].plot()
        cv2.imshow("YOLO11n NCNN - Live Detection", annotated)

        if cv2.waitKey(1) & 0xFF == ord("q"):
            break
finally:
    picam2.stop()
    cv2.destroyAllWindows()
