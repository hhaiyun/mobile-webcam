import socket
import cv2
import numpy as np
import pyvirtualcam

HOST = '0.0.0.0'
PORT = 8000
BUF_SIZE = 1200

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((HOST, PORT))
print(f"Listening on {HOST}:{PORT}")

s.settimeout(0.5)

current_frame_id = None
chunks = {}
total_chunks = 0

with pyvirtualcam.Camera(width=1280, height=720, fps=30) as cam:
    try:
        while True:
            try:
                message, address = s.recvfrom(BUF_SIZE)
            except socket.timeout:
                continue

            if len(message) < 6:
                continue

            # Parse header
            frame_id = (message[0] << 8) | message[1]
            chunk_id = (message[2] << 8) | message[3]
            total_chunks = (message[4] << 8) | message[5]
            chunk = message[6:]

            if current_frame_id is None:
                current_frame_id = frame_id

            # Old frame is incomplete
            if frame_id != current_frame_id:
                current_frame_id = frame_id
                chunks = {}

            chunks[chunk_id] = chunk

            # Check if frame complete
            if len(chunks) == total_chunks:
                # Reassemble
                frame_bytes = b''.join(chunks[i] for i in range(total_chunks))
                chunks = {}

                try:
                    img = cv2.imdecode(np.frombuffer(frame_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
                    if img is not None:
                        img = cv2.resize(img, (1280, 720))
                        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                        cam.send(img)
                        cam.sleep_until_next_frame()
                    else:
                        print("Frame decode failed")
                except Exception as e:
                    print("Decode error:", e)
    except KeyboardInterrupt:
        print("\nInterupt")
    finally:
        s.close()
        cv2.destroyAllWindows()
        print("\nExiting")