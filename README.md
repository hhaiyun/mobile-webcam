### Mobile Webcam
Use your Android device as a webcam for your PC.

### Requirements:
#### PC:
- Python 3.7–3.12
- opencv-python and pyvirtualcam
  - `pip install opencv-python pyvirtualcam`
- Virtual camera supported by pyvirtualcam (e.g., OBS, v4l2loopback, [etc.](https://website-name.comhttps://pypi.org/project/pyvirtualcam/#:~:text=Supported%20virtual%20cameras))

#### Android:
- Device with camera
- Android 7.0 (API 24) or higher

### Usage
1. Download APK and receiver
2. Start receiver: `python receiver.py`
3. Set PC IP in the Android app and hit start