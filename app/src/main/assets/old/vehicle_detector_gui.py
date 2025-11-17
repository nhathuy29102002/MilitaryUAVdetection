import os
import sys
import cv2
import numpy as np
import shutil
import tempfile
import time
import subprocess 
import random 

# --- Thư viện bên ngoài cần thiết ---
# Cần cài đặt: pip install pyqt5 opencv-python ultralytics mss pynput
try:
    from mss import mss
except ImportError:
    print("Cảnh báo: Thiếu thư viện 'mss'. Chức năng chụp màn hình (nếu không dùng gnome-screenshot) sẽ bị vô hiệu hóa.")
    mss = None

# Import thư viện pynput
try:
    from pynput.keyboard import Key, Controller as KeyboardController, Listener
    keyboard = KeyboardController()
except ImportError:
    print("Cảnh báo: Thiếu thư viện 'pynput'.")
    keyboard = None
    Listener = None


from PyQt5.QtCore import Qt, QSize, QDir, QRect, QPoint, QTimer, QCoreApplication, QThread, QRectF
from PyQt5.QtCore import QObject, pyqtSignal, QThreadPool, QRunnable
from PyQt5.QtGui import QPixmap, QImage, QIcon, QPainter, QCursor, QColor
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QPushButton, QLabel, QFileDialog,
    QVBoxLayout, QHBoxLayout, QMessageBox, QAction, QToolBar,
    QSplitter, QListWidget, QGraphicsView, QGraphicsScene, QMenuBar,
    QListWidgetItem, QSizePolicy, QStatusBar, QToolButton, QSlider,
    QLineEdit
)

# Thư viện YOLOv8
try:
    from ultralytics import YOLO
except ImportError:
    print("Lỗi: Thiếu thư viện 'ultralytics'. Vui lòng cài đặt bằng: pip install ultralytics")
    sys.exit(1)

# Buộc PyQt5 render bằng CPU (giải quyết lỗi xung đột DLL)
os.environ["QT_OPENGL"] = "software" 

# --- Các Tín hiệu và Worker ---

class WorkerSignals(QObject):
    file_processed = pyqtSignal(str) 
    result = pyqtSignal(str, str, list, int, int) # original_path, original_image_path (temp), label_data, w, h
    video_processed = pyqtSignal(str, str, str, int, int) # original_path, result_video_path, thumbnail_path, w, h
    
    recording_finished = pyqtSignal(str) 
    
    finished = pyqtSignal()
    error = pyqtSignal(str)

class PredictionWorker(QRunnable):
    """Worker dùng cho xử lý ảnh (Cập nhật: Gửi về W, H)."""
    def __init__(self, model, file_paths, temp_dir, is_batch=False):
        super().__init__()
        self.model = model
        self.file_paths = file_paths if isinstance(file_paths, list) else [file_paths]
        self.temp_dir = temp_dir
        self.is_batch = is_batch 
        self.signals = WorkerSignals()
        
        self.temp_originals_dir = os.path.join(self.temp_dir, 'originals')
        self.temp_labels_dir = os.path.join(self.temp_dir, 'labels')
        os.makedirs(self.temp_originals_dir, exist_ok=True)
        os.makedirs(self.temp_labels_dir, exist_ok=True)

    def run(self):
        try:
            for file_path in self.file_paths:
                filename = os.path.basename(file_path)
                base_name = os.path.splitext(filename)[0]
                
                temp_original_path = os.path.join(self.temp_originals_dir, filename)
                shutil.copy(file_path, temp_original_path)

                img = cv2.imread(temp_original_path)
                if img is None:
                    print(f"Không thể đọc ảnh: {temp_original_path}")
                    continue
                h, w, _ = img.shape

                results = self.model.predict(file_path, save=False, save_txt=True, save_conf=True, 
                                              project=self.temp_labels_dir, name=f'{base_name}_labels', exist_ok=True, verbose=False, iou=0.7)
                
                label_path = os.path.join(self.temp_labels_dir, f'{base_name}_labels', 'labels', f'{base_name}.txt')
                
                if not os.path.exists(label_path):
                    label_dir_path = os.path.join(self.temp_labels_dir, f'{base_name}_labels', 'labels')
                    if os.path.exists(label_dir_path):
                        all_labels = [f for f in os.listdir(label_dir_path) if f.endswith('.txt')]
                        if all_labels:
                            label_path = os.path.join(label_dir_path, all_labels[0])
                        else:
                            open(label_path, 'w').close() 
                    else:
                        os.makedirs(label_dir_path, exist_ok=True)
                        open(label_path, 'w').close() 

                label_data = []
                if os.path.exists(label_path):
                    with open(label_path, 'r') as f:
                        for line in f:
                            parts = line.strip().split()
                            if len(parts) >= 6:
                                try:
                                    label_data.append([
                                        int(parts[0]), float(parts[1]), float(parts[2]),
                                        float(parts[3]), float(parts[4]), float(parts[5])
                                    ])
                                except ValueError:
                                    print(f"Bỏ qua dòng nhãn không hợp lệ: {line}")
                
                if self.is_batch:
                    self.signals.file_processed.emit(file_path)
                else:
                    self.signals.result.emit(file_path, temp_original_path, label_data, w, h)
                    
        except Exception as e:
            self.signals.error.emit(f"Lỗi xử lý file {filename}: {e}")
        finally:
            self.signals.finished.emit()

class VideoWorker(QRunnable):
    """Worker dùng cho xử lý Video (Cập nhật: Gửi về W, H)."""
    def __init__(self, model, file_path, temp_dir):
        super().__init__()
        self.model = model
        self.file_path = file_path
        self.temp_dir = temp_dir
        self.signals = WorkerSignals()
        self.temp_originals_dir = os.path.join(self.temp_dir, 'originals')
        os.makedirs(self.temp_originals_dir, exist_ok=True)

    def _create_thumbnail(self, original_video_path, filename_base):
        thumbnail_path = os.path.join(self.temp_originals_dir, f"{filename_base}_thumb.jpg")
        width, height = 0, 0
        
        try:
            cap = cv2.VideoCapture(original_video_path)
            if not cap.isOpened():
                return None, 0, 0
            
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            thumb_frame = max(1, total_frames // 3)
            cap.set(cv2.CAP_PROP_POS_FRAMES, thumb_frame) 
            
            ret, frame = cap.read()
            cap.release()
            
            if not ret:
                return None, width, height
            
            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            h, w, ch = frame_rgb.shape
            bytes_per_line = ch * w
            q_img = QImage(frame_rgb.data, w, h, bytes_per_line, QImage.Format_RGB888)
            
            pixmap = QPixmap.fromImage(q_img)
            
            painter = QPainter(pixmap)
            play_color = QColor(255, 255, 255, 180) 
            painter.setBrush(play_color)
            painter.setPen(Qt.NoPen)
            
            center_x = pixmap.width() // 2
            center_y = pixmap.height() // 2
            size = min(pixmap.width(), pixmap.height()) // 4
            
            points = [
                QPoint(center_x - size // 2 + 5, center_y - size // 2),
                QPoint(center_x + size // 2 + 5, center_y),
                QPoint(center_x - size // 2 + 5, center_y + size // 2),
            ]
            painter.drawPolygon(*points)
            painter.end()

            pixmap.save(thumbnail_path, "JPG")
            return thumbnail_path, width, height

        except Exception as e:
            print(f"Lỗi tạo thumbnail: {e}")
            return None, width, height

    def run(self):
        try:
            filename = os.path.basename(self.file_path)
            filename_base = os.path.splitext(filename)[0]

            thumbnail_path, w, h = self._create_thumbnail(self.file_path, filename_base)
            
            results = self.model.predict(self.file_path, save=True, 
                                          project=self.temp_dir, name='yolo_video_results', exist_ok=True, verbose=False, iou=0.7)
            
            save_dir = results[0].save_dir
            result_video_path = None
            
            processed_files = []
            for f in os.listdir(save_dir):
                if f.endswith(('.mp4', '.avi')):
                    processed_files.append(os.path.join(save_dir, f))
            
            if processed_files:
                result_video_path = max(processed_files, key=os.path.getmtime)
            
            if not result_video_path or not os.path.exists(result_video_path):
                 result_video_path = os.path.join(save_dir, filename)
                 if not os.path.exists(result_video_path):
                    raise FileNotFoundError(f"Không tìm thấy video kết quả sau xử lý tại {save_dir}")
            
            self.signals.video_processed.emit(self.file_path, result_video_path, thumbnail_path, w, h)
            
        except Exception as e:
            self.signals.error.emit(f"Lỗi xử lý video {filename}: {e}")
        finally:
            self.signals.finished.emit()

class RecordingWorker(QRunnable):
    """Worker để quay video màn hình trong một luồng riêng biệt."""
    def __init__(self, rect, temp_dir, fps=20):
        super().__init__()
        self.signals = WorkerSignals()
        self.rect = rect
        self.temp_dir = temp_dir
        self.fps = fps
        self.is_running = True
        
        self.width = rect.width() - (rect.width() % 2)
        self.height = rect.height() - (rect.height() % 2)
        self.monitor = {
            'top': rect.top(), 
            'left': rect.left(), 
            'width': self.width, 
            'height': self.height
        }

    def run(self):
        video_path = os.path.join(self.temp_dir, f"recording_{int(time.time())}.mp4")
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        
        try:
            writer = cv2.VideoWriter(video_path, fourcc, self.fps, (self.width, self.height))
            if not writer.isOpened():
                raise Exception("Không thể khởi tạo VideoWriter.")
                
            sct = mss()
            
            while self.is_running:
                start_time = time.time()
                
                img = sct.grab(self.monitor)
                frame = np.array(img)
                frame_bgr = cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
                
                writer.write(frame_bgr)
                
                elapsed = time.time() - start_time
                sleep_time = (1.0 / self.fps) - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)

            writer.release()
            self.signals.recording_finished.emit(video_path)
            
        except Exception as e:
            self.signals.error.emit(f"Lỗi khi đang quay: {e}")
        finally:
            if 'writer' in locals() and writer.isOpened():
                writer.release()

    def stop(self):
        self.is_running = False

# --- Chức năng Chụp màn hình (Sử dụng QScreen.grabWindow) ---

class ScreenshotTool(QMainWindow):
    """
    Cửa sổ Overlay để chụp/quay màn hình.
    """
    selection_finished = pyqtSignal(QPixmap) 
    recording_started = pyqtSignal(QRect)

    def __init__(self, parent=None):
        super().__init__(parent)
        
        self.setWindowFlags(Qt.SplashScreen | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint)
        self.setWindowState(Qt.WindowFullScreen)
        self.setCursor(QCursor(Qt.CrossCursor)) 
        self.setAttribute(Qt.WA_TranslucentBackground, True)
        
        self.desktop_pixmap = None
        self.start_point = QPoint()
        self.end_point = QPoint()
        self.selecting = False
        self.is_recording_mode = False

    def start_snip(self, pixmap: QPixmap, is_recording=False):
        """Bắt đầu chụp với ảnh nền là pixmap."""
        self.desktop_pixmap = pixmap
        self.is_recording_mode = is_recording
        self.showFullScreen()
        self.activateWindow()
        self.setCursor(QCursor(Qt.CrossCursor))

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.start_point = event.pos()
            self.selecting = True
            
    def mouseMoveEvent(self, event):
        if self.selecting:
            self.end_point = event.pos()
            self.update() 

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.LeftButton and self.selecting:
            self.selecting = False
            self.setCursor(QCursor(Qt.ArrowCursor)) 
            self.hide()
            
            selection_rect = QRect(self.start_point, self.end_point).normalized()
            
            if selection_rect.width() < 10 or selection_rect.height() < 10:
                self.parent().show_status_message("Vùng chọn quá nhỏ.", 3000)
                self.parent().showNormal()
                return

            if self.is_recording_mode:
                self.recording_started.emit(selection_rect)
            else:
                snipped_pixmap = self.desktop_pixmap.copy(selection_rect)
                self.selection_finished.emit(snipped_pixmap)
            
            self.parent().showNormal()
            
    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.selecting = False
            self.setCursor(QCursor(Qt.ArrowCursor)) 
            self.hide()
            if self.parent():
                self.parent().set_transparent_mode(False)
            self.parent().showNormal() 
            
    def paintEvent(self, event):
        """Vẽ ảnh desktop, phủ mờ, sau đó "xóa" vùng chọn."""
        painter = QPainter(self)
        if not self.desktop_pixmap:
            painter.fillRect(self.rect(), QColor(0, 0, 0, 150)) 
            return

        painter.drawPixmap(self.rect(), self.desktop_pixmap)
        overlay_color = QColor(0, 0, 0, 120) 
        painter.fillRect(self.rect(), overlay_color)

        if self.selecting:
            selection_rect = QRect(self.start_point, self.end_point).normalized()
            painter.setCompositionMode(QPainter.CompositionMode_Clear)
            painter.fillRect(selection_rect, Qt.transparent)
            painter.setCompositionMode(QPainter.CompositionMode_SourceOver)
            
            border_color = QColor(255, 0, 0, 255) if self.is_recording_mode else QColor(0, 150, 255, 255)
            painter.setPen(border_color) 
            painter.drawRect(selection_rect)

# --- Các lớp UI Chính ---

class MainViewer(QGraphicsView):
    drag_enter_signal = pyqtSignal()
    drag_leave_signal = pyqtSignal()
    drop_signal = pyqtSignal(list)
    
    def __init__(self, parent=None):
        super().__init__(parent)
        
        self.scene = QGraphicsScene(self)
        self.setScene(self.scene)
        self.current_pixmap_item = None 
        self.current_pixmap = None 
        
        self.setRenderHint(QPainter.Antialiasing)
        self.setDragMode(QGraphicsView.ScrollHandDrag)
        self.setStyleSheet("background-color: #E0E0E0;") 
        
        self.setAcceptDrops(True)
        
        # === SỬA LỖI FIT TO VIEW: Thêm cờ theo dõi zoom ===
        self.user_has_zoomed = False

    def set_image(self, qimage: QImage):
        """Tải ảnh tĩnh MỚI, reset scene và fit to view."""
        new_pixmap = QPixmap.fromImage(qimage)
        self.current_pixmap = new_pixmap 
        
        self.scene.clear()
        self.current_pixmap_item = self.scene.addPixmap(new_pixmap)
            
        self.scene.setSceneRect(self.scene.itemsBoundingRect())
        self.resetTransform()
        
        # === SỬA LỖI FIT TO VIEW: Reset cờ zoom ===
        self.user_has_zoomed = False 
        
        self.fitInView(self.scene.sceneRect(), Qt.KeepAspectRatio)

    def update_video_frame(self, qimage: QImage):
        """Cập nhật frame video mà không thay đổi scene rect hay zoom."""
        new_pixmap = QPixmap.fromImage(qimage)
        self.current_pixmap = new_pixmap

        if self.current_pixmap_item:
            self.current_pixmap_item.setPixmap(new_pixmap)
        else:
            self.current_pixmap_item = self.scene.addPixmap(new_pixmap)
            self.scene.setSceneRect(self.scene.itemsBoundingRect())
            self.resetTransform()
            
            # === SỬA LỖI FIT TO VIEW: Reset cờ zoom ===
            self.user_has_zoomed = False
            
            self.fitInView(self.scene.sceneRect(), Qt.KeepAspectRatio)

    def clear_view(self):
        self.scene.clear()
        self.current_pixmap = None
        self.current_pixmap_item = None
        self.scene.setSceneRect(QRectF()) # Reset SceneRect
        self.resetTransform()
        
        # === SỬA LỖI FIT TO VIEW: Reset cờ zoom ===
        self.user_has_zoomed = False

    def wheelEvent(self, event):
        """Zoom bằng Ctrl+Scroll."""
        if event.modifiers() == Qt.ControlModifier:
            zoom_in_factor = 1.15
            zoom_out_factor = 1 / zoom_in_factor

            if event.angleDelta().y() > 0:
                self.scale(zoom_in_factor, zoom_in_factor)
            else:
                self.scale(zoom_out_factor, zoom_out_factor)
            
            # === SỬA LỖI FIT TO VIEW: Đặt cờ zoom ===
            self.user_has_zoomed = True
        else:
            super().wheelEvent(event)
            
    def mousePressEvent(self, event):
        """Fit to View bằng chuột giữa."""
        if event.button() == Qt.MiddleButton:
            if self.current_pixmap_item:
                
                # === SỬA LỖI FIT TO VIEW: Reset cờ zoom ===
                self.user_has_zoomed = False
                
                self.fitInView(self.scene.sceneRect(), Qt.KeepAspectRatio)
        else:
            super().mousePressEvent(event)
            
    def resizeEvent(self, event):
        """Tự động Fit to View khi thay đổi kích thước cửa sổ."""
        super().resizeEvent(event)
        
        # === SỬA LỖI FIT TO VIEW: Chỉ fit nếu người dùng không tự zoom ===
        if self.current_pixmap_item and not self.user_has_zoomed:
            if self.scene.sceneRect().width() > 0 or self.scene.sceneRect().height() > 0:
                self.fitInView(self.scene.sceneRect(), Qt.KeepAspectRatio)

    # --- Xử lý Drag/Drop Events ---
    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            self.setStyleSheet("background-color: #ADD8E6;")
        else:
            event.ignore()

    def dragLeaveEvent(self, event):
        self.setStyleSheet("background-color: #E0E0E0;")

    def dropEvent(self, event):
        self.setStyleSheet("background-color: #E0E0E0;")
        
        paths = []
        for url in event.mimeData().urls():
            path = url.toLocalFile()
            if os.path.exists(path):
                paths.append(path)
                
        if paths:
            self.drop_signal.emit(paths)
            
        event.acceptProposedAction()


class VehicleDetectorGUI(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Vehicle Detector - YOLOv8")
        self.setGeometry(100, 100, 1200, 800)

        # --- Trạng thái Mô hình & Dữ liệu ---
        self.model = None
        self.class_names = {} 
        self.class_colors = {} 
        
        self.current_image_path = None 
        self.auto_save = False
        self.export_location = None
        self.file_list = [] 
        self.save_status = {} 
        self.listed_file_names = set() 
        self.file_metadata = {} 
        self.file_id_counter = 0 

        self.temp_dir = tempfile.mkdtemp()
        self.temp_image_result_dir = os.path.join(self.temp_dir, 'yolo_image_results')
        
        self.threadpool = QThreadPool()
        
        self.widget_styles = {}
        
        # --- Video Playback Attributes ---
        self.video_timer = QTimer(self)
        self.video_capture = None
        self.current_video_result_path = None
        self.video_timer.timeout.connect(self._next_video_frame) 

        self.current_recorder = None
        self.key_listener = None # Listener cho phím 'Esc'

        # --- Trạng thái Show/Hide ---
        self.is_box_visible = True
        self.is_class_visible = True
        self.is_confidence_visible = True
        
        self.menu_zoom_multiplier = 3.0
        
        # --- Đường dẫn icon ---
        self.icon_image_path = "D:/model_completed/executive/image_icon.png".replace("\\", "/")
        self.icon_video_path = "D:/model_completed/executive/video_icon.png".replace("\\", "/")
        self.icon_image_default = QIcon(self.icon_image_path)
        self.icon_video_default = QIcon(self.icon_video_path)
        
        self.init_ui()
        
        self.screenshot_tool = ScreenshotTool(self)
        self.screenshot_tool.selection_finished.connect(self.run_screenshot_prediction)
        self.screenshot_tool.recording_started.connect(self.start_recording_worker)
        
        self.setStatusBar(QStatusBar(self))
        
        self._set_controls_enabled(False)
        
        # Bắt đầu listener phím (để dừng quay video)
        self.start_key_listener()

    # --- Video Functions ---
    def set_transparent_mode(self, enabled):
        """Đặt cửa sổ ứng dụng ở chế độ trong suốt (opacity 0.01) hoặc khôi phục (opacity 1.0)."""
        # Lấy cờ hiện tại của cửa sổ
        current_flags = self.windowFlags()
        
        # Định nghĩa các cờ cần thêm/xóa
        flags_to_modify = Qt.WindowStaysOnTopHint | Qt.WindowTransparentForInput
        
        if enabled:
            # Bật chế độ trong suốt và đưa lên trên cùng
            self.setWindowFlags(current_flags | flags_to_modify)
            self.setWindowOpacity(0.01) # Rất gần 0 để đạt hiệu ứng trong suốt
            self.show()
        else:
            # Khôi phục cờ gốc và độ trong suốt
            # Loại bỏ các cờ đã thêm
            self.setWindowFlags(current_flags & ~flags_to_modify)
            self.setWindowOpacity(1.0) # Khôi phục độ mờ hoàn toàn
            self.show()

    def _start_video_playback(self, result_path):
        """Bắt đầu chạy video trong MainViewer."""
        self._stop_video_playback()
        
        self.video_capture = cv2.VideoCapture(result_path)
        
        if not self.video_capture.isOpened():
            self.show_status_message(f"Lỗi: Không thể mở video kết quả '{os.path.basename(result_path)}'.", 5000)
            self.video_capture = None
            return

        fps = self.video_capture.get(cv2.CAP_PROP_FPS)
        total_frames = int(self.video_capture.get(cv2.CAP_PROP_FRAME_COUNT))
        if fps <= 0: fps = 30 
        
        self.video_slider.setRange(0, total_frames)
        self.video_slider.setValue(0)
        
        self.video_timer.start(int(1000 / fps)) 
        self.current_video_result_path = result_path
        self.btn_play_pause.setIcon(QIcon.fromTheme("media-playback-pause"))
        self.show_status_message(f"Đang phát video với FPS: {fps:.1f}", 2000)
        
    def _stop_video_playback(self):
        """Dừng chạy video và giải phóng tài nguyên."""
        if self.video_timer.isActive():
            self.video_timer.stop()
        if self.video_capture:
            self.video_capture.release()
            self.video_capture = None
        self.current_video_result_path = None
        self.btn_play_pause.setIcon(QIcon.fromTheme("media-playback-start"))
        
    def _toggle_play_pause(self):
        """Bật/Tắt Play/Pause cho video."""
        if self.video_timer.isActive():
            self.video_timer.stop()
            self.btn_play_pause.setIcon(QIcon.fromTheme("media-playback-start"))
        else:
            if self.video_capture:
                fps = self.video_capture.get(cv2.CAP_PROP_FPS)
                if fps <= 0: fps = 30
                self.video_timer.start(int(1000 / fps))
                self.btn_play_pause.setIcon(QIcon.fromTheme("media-playback-pause"))
                
    def _seek_video(self, position):
        """Di chuyển đến frame được chọn bởi Slider."""
        if self.video_capture:
            self.video_capture.set(cv2.CAP_PROP_POS_FRAMES, position)
            if not self.video_timer.isActive():
                self._next_video_frame() 

    def _next_video_frame(self):
        """Đọc frame tiếp theo và cập nhật MainViewer."""
        if self.video_capture and self.video_capture.isOpened():
            ret, frame = self.video_capture.read()
            if ret:
                frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                height, width, channel = frame.shape
                bytes_per_line = 3 * width
                q_image = QImage(frame.data, width, height, bytes_per_line, QImage.Format_RGB888)
                
                self.main_viewer.update_video_frame(q_image) 
                
                frame_num = self.video_capture.get(cv2.CAP_PROP_POS_FRAMES)
                total_frames = self.video_capture.get(cv2.CAP_PROP_FRAME_COUNT)
                
                if not self.video_slider.isSliderDown():
                    self.video_slider.setValue(int(frame_num))
                    
                formatted_name = self._format_filename(self.current_image_path, max_len=30)
                self.label_filename.setText(f"Video: {formatted_name} (Frame {int(frame_num)}/{int(total_frames)})")
                
            else:
                self.video_capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
        else:
            self._stop_video_playback()

    def __del__(self):
        self._stop_video_playback()
        if self.current_recorder:
             self.current_recorder.stop()
        if self.key_listener:
             self.key_listener.stop()
        if os.path.exists(self.temp_dir):
            try:
                shutil.rmtree(self.temp_dir, ignore_errors=True)
            except OSError as e:
                print(f"Cảnh báo: Không thể xóa thư mục tạm thời {self.temp_dir}: {e}")

    def show_status_message(self, message, timeout=0):
        """Hiển thị thông báo trên StatusBar."""
        if self.statusBar():
            self.statusBar().showMessage(message, timeout)
            
    def _format_filename(self, path, max_len=60):
        """Định dạng tên file thành 'basename...ext'."""
        if not path:
            return "N/A"
        
        filename = os.path.basename(path)
        if len(filename) <= max_len:
            return filename
        
        name, ext = os.path.splitext(filename)
        keep_len = max_len - 3 - len(ext)
        
        if keep_len < 1:
            return filename[:max_len-3] + "..."
            
        return f"{name[:keep_len]}...{ext}"

    def init_ui(self):
        central_widget = QWidget()
        main_layout = QHBoxLayout(central_widget)
        main_layout.setContentsMargins(5, 0, 0, 0)
        main_layout.setSpacing(0)
        self.setCentralWidget(central_widget)

        self.create_left_toolbar()
        
        center_panel_widget = QWidget()
        center_layout = QVBoxLayout(center_panel_widget)
        center_layout.setContentsMargins(0, 0, 0, 0)
        
        self.main_viewer = MainViewer()
        
        self.main_viewer.drop_signal.connect(self._handle_drop)
        
        self.video_controls_widget = QWidget()
        video_layout = QHBoxLayout(self.video_controls_widget)
        video_layout.setContentsMargins(0, 5, 0, 5)
        
        self.btn_play_pause = QPushButton()
        self.btn_play_pause.setIcon(QIcon.fromTheme("media-playback-start")) 
        self.btn_play_pause.clicked.connect(self._toggle_play_pause)
        
        self.video_slider = QSlider(Qt.Horizontal)
        self.video_slider.sliderMoved.connect(self._seek_video)
        
        video_layout.addWidget(self.btn_play_pause)
        video_layout.addWidget(self.video_slider)
        
        center_layout.addWidget(self.main_viewer)
        center_layout.addWidget(self.video_controls_widget)
        self.video_controls_widget.setVisible(False) 

        right_panel = self.create_right_panel()

        size_policy = QSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        right_panel.setSizePolicy(size_policy)
        right_panel.setMinimumWidth(200)
        right_panel.setMaximumWidth(400)

        main_splitter = QSplitter(Qt.Horizontal)
        main_splitter.addWidget(center_panel_widget)
        main_splitter.addWidget(right_panel)
        main_splitter.setSizes([950, 250])
        main_splitter.setChildrenCollapsible(False)

        main_layout.addWidget(main_splitter)
        
        self.create_menu_bar()
    
    def create_left_toolbar(self):
        """Tạo Left Toolbar với icon kích thước tùy chỉnh."""
        toolbar = QToolBar("Chức năng")
        toolbar.setMovable(False)
        toolbar.setFloatable(False)
        toolbar.setOrientation(Qt.Vertical)
        toolbar.setFixedWidth(100) 
        
        # === SỬA KÍCH THƯỚC ICON: Thay đổi giá trị ở đây ===
        BUTTON_SIZE = QSize(90, 90)
        IMAGE_ICON_SIZE = QSize(72, 72)     # Icon chính (Import Image, Video, Screenshot)
        DEFAULT_ICON_SIZE = QSize(56, 56)   # Icon phụ (Next, Prev, Fit to View)
        # ===============================================
        
        # Đường dẫn Icon
        self.icon_path_model = "D:/model_completed/executive/importmodel.png".replace("\\", "/")
        self.icon_path_model_check = "D:/model_completed/executive/importmodel-check.png".replace("\\", "/")
        self.icon_path_import_image = "D:/model_completed/executive/importimage.png".replace("\\", "/")
        self.icon_path_import_video = "D:/model_completed/executive/importvideo.png".replace("\\", "/")
        self.icon_path_screenshot = "D:/model_completed/executive/screenshot.png".replace("\\", "/")
        self.icon_path_previous = "D:/model_completed/executive/Previous.png".replace("\\", "/")
        self.icon_path_next = "D:/model_completed/executive/Next.png".replace("\\", "/")
        
        self.dependent_widgets = []

        # Helper cho các nút có chữ
        def create_text_button(text, icon_name, slot, custom_icon_size=DEFAULT_ICON_SIZE):
            tool_button = QToolButton(self)
            
            if " " in text:
                 parts = text.split(" ", 1) 
                 formatted_text = f"{parts[0]}\n{parts[1]}"
            else:
                 formatted_text = text
                 
            tool_button.setText(formatted_text)
            tool_button.setIcon(QIcon.fromTheme(icon_name)) 
            tool_button.setToolButtonStyle(Qt.ToolButtonTextUnderIcon)
            
            tool_button.setFixedSize(BUTTON_SIZE) 
            tool_button.setIconSize(custom_icon_size) 
            tool_button.clicked.connect(slot)
            
            style = "QToolButton { text-align: center; font-size: 9pt; background-color: #f0f0f0; border: 1px solid #888; }"
            tool_button.setStyleSheet(style)
            self.widget_styles[tool_button] = style
            
            self.dependent_widgets.append(tool_button)
            return tool_button

        # Helper cho các nút chỉ có ảnh (dùng setIconSize)
        def create_image_button(icon_path, slot, tooltip, icon_size=IMAGE_ICON_SIZE):
            tool_button = QToolButton(self)
            tool_button.setIcon(QIcon(icon_path))
            tool_button.setToolTip(tooltip)
            tool_button.setToolButtonStyle(Qt.ToolButtonIconOnly)
            tool_button.setFixedSize(BUTTON_SIZE) 
            tool_button.setIconSize(icon_size)
            tool_button.clicked.connect(slot)
            
            style = """
                QToolButton { background-color: #f0f0f0; border: 1px solid #888; }
                QToolButton:hover { background-color: #e0e0e0; }
                QToolButton:pressed { background-color: #d0d0d0; }
            """
            tool_button.setStyleSheet(style)
            self.widget_styles[tool_button] = style
            
            self.dependent_widgets.append(tool_button)
            return tool_button

        # 1. Nút Import Model (vẫn dùng border-image)
        self.btn_import_model = QToolButton(self)
        self.btn_import_model.setFixedSize(BUTTON_SIZE)
        self.btn_import_model.clicked.connect(self.import_model)
        self.btn_import_model.setToolTip("Import Model (.pt) (Q)")
        
        style_model = f"""
            QToolButton {{
                border-image: url("{self.icon_path_model}") 0 0 0 0 stretch stretch;
                border: 1px solid #888;
                background-color: #f0f0f0;
            }}
            QToolButton:hover {{ background-color: #e0e0e0; border-image: url("{self.icon_path_model}"); }}
            QToolButton:pressed {{ background-color: #d0d0d0; border-image: url("{self.icon_path_model}"); }}
        """
        self.btn_import_model.setStyleSheet(style_model)
        self.widget_styles[self.btn_import_model] = style_model
        toolbar.addWidget(self.btn_import_model)
        
        # 2. Các nút khác (dùng setIconSize)
        toolbar.addWidget(create_image_button(self.icon_path_import_image, self.import_image, "Import Image", icon_size=IMAGE_ICON_SIZE))
        toolbar.addWidget(create_image_button(self.icon_path_import_video, self.import_video, "Import Video", icon_size=IMAGE_ICON_SIZE))
        toolbar.addWidget(create_image_button(self.icon_path_screenshot, self.process_screenshot, "Process Screenshot (Chụp ảnh)", icon_size=IMAGE_ICON_SIZE)) 
        
        toolbar.addSeparator()
        toolbar.addWidget(create_image_button(self.icon_path_previous, self.previous_image, "Previous", icon_size=DEFAULT_ICON_SIZE))
        toolbar.addWidget(create_image_button(self.icon_path_next, self.next_image, "Next", icon_size=DEFAULT_ICON_SIZE))
        
        # 3. Nút Fit to View
        toolbar.addSeparator()
        toolbar.addWidget(create_text_button("Fit to View", "zoom-fit-best", self.fit_to_view, custom_icon_size=DEFAULT_ICON_SIZE))

        self.addToolBar(Qt.LeftToolBarArea, toolbar)
        
    def create_menu_bar(self):
        menu_bar = QMenuBar(self)
        
        file_menu = menu_bar.addMenu("File")
        
        self.act_load_folder = file_menu.addAction("Load Folder"); self.act_load_folder.triggered.connect(self.load_folder)
        self.act_autosave = file_menu.addAction("Auto-save result (OFF)"); self.act_autosave.triggered.connect(self.toggle_autosave)
        self.act_autosave.setCheckable(True)
        self.act_export_loc = file_menu.addAction("Choose export location"); self.act_export_loc.triggered.connect(self.choose_export_location)
        
        self.act_load_recording = file_menu.addAction("Area Recorder (Quay Vùng)")
        self.act_load_recording.triggered.connect(self.process_screen_recording)

        view_menu = menu_bar.addMenu("View")
        
        self.act_zoom_in = view_menu.addAction("Zoom In")
        self.act_zoom_in.triggered.connect(self.menu_zoom_in)
        
        self.act_zoom_out = view_menu.addAction("Zoom Out")
        self.act_zoom_out.triggered.connect(self.menu_zoom_out)
        
        self.act_show_hide_box = view_menu.addAction("Show/Hide Boundingbox (ON)") 
        self.act_show_hide_box.triggered.connect(self.toggle_bounding_box); self.act_show_hide_box.setCheckable(True); self.act_show_hide_box.setChecked(True)
        
        self.act_show_hide_class = view_menu.addAction("Show/hide Class Name (ON)")
        self.act_show_hide_class.triggered.connect(self.toggle_class_name); self.act_show_hide_class.setCheckable(True); self.act_show_hide_class.setChecked(True)

        self.act_show_hide_conf = view_menu.addAction("Show/Hide Confidence (ON)")
        self.act_show_hide_conf.triggered.connect(self.toggle_confidence); self.act_show_hide_conf.setCheckable(True); self.act_show_hide_conf.setChecked(True)
        
        self.setMenuBar(menu_bar)
        
        self.dependent_widgets.extend([
            self.act_load_folder, self.act_autosave, self.act_export_loc, self.act_load_recording,
            self.act_zoom_in, self.act_zoom_out, self.act_show_hide_box,
            self.act_show_hide_class, self.act_show_hide_conf
        ])

    def menu_zoom_in(self):
        """Zoom In từ menu với multiplier gấp 3."""
        if not self.main_viewer.current_pixmap_item: return
        self.main_viewer.user_has_zoomed = True # Đánh dấu người dùng tự zoom
        factor = 1.15 ** self.menu_zoom_multiplier
        self.main_viewer.scale(factor, factor)
    
    def menu_zoom_out(self):
        """Zoom Out từ menu với multiplier gấp 3."""
        if not self.main_viewer.current_pixmap_item: return
        self.main_viewer.user_has_zoomed = True # Đánh dấu người dùng tự zoom
        factor = 1.15 ** self.menu_zoom_multiplier
        self.main_viewer.scale(1/factor, 1/factor)

    def create_right_panel(self):
        detail_widget = QWidget()
        vbox = QVBoxLayout(detail_widget)
        vbox.setContentsMargins(5, 5, 5, 0)
        vbox.setSpacing(5)
        
        self.label_filename = QLabel("Tên file: (Chưa có ảnh)")
        self.label_filename.setWordWrap(True)
        self.label_filename.setMaximumHeight(40)
        
        self.label_size = QLabel("Kích thước: N/A")
        
        vbox.addWidget(self.label_filename)
        vbox.addWidget(self.label_size)
        
        button_layout = QHBoxLayout()
        self.btn_save = QPushButton("Save")
        self.btn_clear = QPushButton("Clear")
        
        self.btn_save.clicked.connect(self.save_result)
        self.btn_clear.clicked.connect(self.clear_all)

        button_layout.addWidget(self.btn_save)
        button_layout.addWidget(self.btn_clear)
        vbox.addLayout(button_layout)
        
        self.dependent_widgets.extend([self.btn_save, self.btn_clear])
        
        search_widget = QWidget()
        search_layout = QHBoxLayout(search_widget)
        search_layout.setContentsMargins(0, 5, 0, 5)
        search_layout.setSpacing(3)

        self.search_bar = QLineEdit()
        self.search_bar.setPlaceholderText("Tìm kiếm file...")
        self.search_bar.textChanged.connect(self._filter_file_list)
        
        search_button = QPushButton("Search")
        search_button.clicked.connect(self._filter_file_list)
        
        search_button.setMaximumWidth(60)
        search_button.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
        
        search_layout.addWidget(self.search_bar, stretch=1)
        search_layout.addWidget(search_button, stretch=0)
        
        vbox.addWidget(search_widget)
        
        self.list_file = QListWidget()
        
        self.list_file.setStyleSheet("""
            QListWidget {
                background-color: #FFFFFF;
                border: none;
            }
            QListWidget::item {
                background-color: #E0E0E0;
                border: 1px solid white;
                border-radius: 2px;
                margin: 1px;
                padding: 2px;
            }
            QListWidget::item:selected {
                background-color: #B0C4DE;
            }
            QListWidget::item:hover {
                background-color: #D3D3D3;
            }
        """)
        
        self.list_file.setViewMode(QListWidget.IconMode) 
        self.list_file.setIconSize(QSize(80, 60)) 
        self.list_file.setWindowTitle("File List")
        self.list_file.itemClicked.connect(self.load_selected_file) 
        
        self.list_file.setGridSize(QSize(95, 100)) 
        
        self.list_file.setFlow(QListWidget.LeftToRight)
        self.list_file.setWrapping(True)
        self.list_file.setResizeMode(QListWidget.Adjust) # Tự động điều chỉnh cột
        self.list_file.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        
        self.list_file.setTextElideMode(Qt.ElideRight)

        vbox.addWidget(self.list_file, stretch=1)
        
        view_mode_widget = QWidget()
        view_mode_layout = QHBoxLayout(view_mode_widget)
        view_mode_layout.setContentsMargins(0, 5, 0, 0)
        view_mode_layout.setSpacing(2)
        
        btn_view_icon = QPushButton("Icon")
        btn_view_detail = QPushButton("Detail")
        btn_view_contents = QPushButton("Contents")
        
        for btn in [btn_view_icon, btn_view_detail, btn_view_contents]:
            btn.setMaximumHeight(25)
        
        btn_view_icon.clicked.connect(self._set_view_icon)
        btn_view_detail.clicked.connect(self._set_view_detail)
        btn_view_contents.clicked.connect(self._set_view_contents)
        
        view_mode_layout.addWidget(btn_view_icon)
        view_mode_layout.addWidget(btn_view_detail)
        view_mode_layout.addWidget(btn_view_contents)
        vbox.addWidget(view_mode_widget, stretch=0)
        
        return detail_widget
    
    def _set_controls_enabled(self, enabled):
        """Kích hoạt hoặc vô hiệu hóa các nút phụ thuộc vào model."""
        
        disabled_style = "background-color: #E0E0E0; color: #888; border: 1px solid #AAA;"
        
        for widget in self.dependent_widgets:
            widget.setEnabled(enabled)
            
            if isinstance(widget, QToolButton):
                if not enabled:
                    original_style = self.widget_styles.get(widget, "")
                    if "border-image" in original_style:
                        widget.setStyleSheet(f"QToolButton {{ {disabled_style} border-image: none; }}")
                    elif widget.toolButtonStyle() == Qt.ToolButtonIconOnly:
                        widget.setStyleSheet(f"QToolButton {{ {disabled_style} icon-size: 0px; }}")
                    else:
                        widget.setStyleSheet(f"QToolButton {{ {disabled_style} }}")
                else:
                    widget.setStyleSheet(self.widget_styles.get(widget, ""))
            
            elif isinstance(widget, QPushButton):
                if not enabled:
                    if widget not in self.widget_styles:
                        self.widget_styles[widget] = widget.styleSheet()
                    widget.setStyleSheet(disabled_style)
                else:
                    widget.setStyleSheet(self.widget_styles.get(widget, ""))

        if enabled:
            self.btn_clear.setEnabled(self.list_file.count() > 0)
        
    def _set_view_icon(self):
        self.list_file.setViewMode(QListWidget.IconMode)
        self.list_file.setIconSize(QSize(80, 60))
        self.list_file.setGridSize(QSize(95, 100))
        self.list_file.setFlow(QListWidget.LeftToRight)
        self.list_file.setWrapping(True)
        self._update_list_item_text_format('icon')

    def _set_view_detail(self):
        self.list_file.setViewMode(QListWidget.ListMode)
        self.list_file.setIconSize(QSize(24, 24))
        self.list_file.setGridSize(QSize(0, 30))
        self.list_file.setFlow(QListWidget.TopToBottom)
        self.list_file.setWrapping(False)
        self._update_list_item_text_format('detail')

    def _set_view_contents(self):
        self.list_file.setViewMode(QListWidget.ListMode)
        self.list_file.setIconSize(QSize(64, 64))
        self.list_file.setGridSize(QSize(0, 75))
        self.list_file.setFlow(QListWidget.TopToBottom)
        self.list_file.setWrapping(False)
        self._update_list_item_text_format('contents')

    def _update_list_item_text_format(self, mode):
        """Cập nhật text của tất cả item dựa trên view mode."""
        for i in range(self.list_file.count()):
            item = self.list_file.item(i)
            original_path = item.toolTip()
            metadata = self.file_metadata.get(original_path)
            
            if not metadata:
                continue

            is_video = metadata['type'] == 'video'

            if mode == 'icon':
                thumb_path = metadata.get('thumbnail_path') if is_video else metadata.get('original_path')
                if thumb_path and os.path.exists(thumb_path):
                    item.setIcon(QIcon(thumb_path))
                else:
                    item.setIcon(self.icon_video_default if is_video else self.icon_image_default)
                
                item.setText(self._format_filename(original_path, max_len=20))
            
            elif mode == 'detail':
                item.setIcon(self.icon_video_default if is_video else self.icon_image_default)
                item.setText(self._format_filename(original_path, max_len=100))
            
            elif mode == 'contents':
                thumb_path = metadata.get('thumbnail_path') if is_video else metadata.get('original_path')
                if thumb_path and os.path.exists(thumb_path):
                    item.setIcon(QIcon(thumb_path))
                else:
                    item.setIcon(self.icon_video_default if is_video else self.icon_image_default)

                w = metadata.get('width', 0)
                h = metadata.get('height', 0)
                
                formatted_name = self._format_filename(original_path, max_len=40)
                original_dir = os.path.dirname(original_path)
                if len(original_dir) > 40:
                    original_dir = original_dir[:20] + "..." + original_dir[-17:]
                
                size_str = f"{w}x{h}"
                item.setText(f"{formatted_name}\n{original_dir}\n{size_str}")
                
    def _filter_file_list(self):
        query = self.search_bar.text().lower().strip()
        
        if not query:
            self._show_all_list_items()
            return
            
        if len(query) < 3 and self.sender() == self.search_bar:
            return

        for i in range(self.list_file.count()):
            item = self.list_file.item(i)
            file_name = item.toolTip().lower()
            
            if query in file_name:
                item.setHidden(False)
            else:
                item.setHidden(True)
                
    def _show_all_list_items(self):
        for i in range(self.list_file.count()):
            self.list_file.item(i).setHidden(False)
            
    def _handle_drop(self, paths):
        """Xử lý các file/folder được thả vào MainViewer."""
        if not self.model:
            self.show_status_message("Lỗi: Vui lòng load model trước khi import.", 5000)
            return
            
        all_image_paths = []
        supported_extensions = ('.jpg', '.jpeg', '.png')
        
        for path in paths:
            if os.path.isdir(path):
                for root, _, files in os.walk(path):
                    for f in files:
                        if f.lower().endswith(supported_extensions):
                            all_image_paths.append(os.path.join(root, f))
            elif os.path.isfile(path):
                if path.lower().endswith(supported_extensions):
                    all_image_paths.append(path)
                    
        if all_image_paths:
            self.show_status_message(f"Đã thả {len(all_image_paths)} ảnh. Đang xử lý...", 3000)
            self.run_prediction_worker(all_image_paths, is_batch=True)
        else:
            self.show_status_message("Không tìm thấy file ảnh hợp lệ trong các file đã thả.", 3000)

    def add_file_to_list(self, file_path):
        """Được gọi bởi Worker khi một file ảnh trong batch được xử lý xong."""
        file_name = os.path.basename(file_path)
        
        if file_name in self.listed_file_names: 
            return
            
        original_img_path = os.path.join(self.temp_dir, 'originals', file_name)
        
        label_data = [] 
        label_path = os.path.join(self.temp_dir, 'labels', os.path.splitext(file_name)[0] + '_labels', 'labels', os.path.splitext(file_name)[0] + '.txt')

        w, h = 0, 0
        try:
            img = cv2.imread(original_img_path)
            h, w, _ = img.shape
        except Exception:
            pass

        self.file_id_counter += 1
        self.file_metadata[file_path] = {
            'type': 'image', 
            'original_path': original_img_path, 
            'label_path': label_path, 
            'label_data': label_data,
            'id': self.file_id_counter,
            'save_status': False,
            'width': w,
            'height': h
        }

        current_view_mode = self.list_file.viewMode()
        is_detail_view = (current_view_mode == QListWidget.ListMode and self.list_file.gridSize().height() <= 30)
        
        if is_detail_view:
            icon = QIcon(self.icon_image_default)
        else:
            temp_pixmap = QPixmap()
            if temp_pixmap.load(original_img_path):
                icon = QIcon(temp_pixmap)
            else:
                icon = QIcon(self.icon_image_default)
        
        item = QListWidgetItem(icon, self._format_filename(file_path, max_len=20))
        item.setToolTip(file_path) 
        
        self.list_file.addItem(item)
        self.file_list.append(file_path)
        self.listed_file_names.add(file_name) 
        
        self.btn_clear.setEnabled(True)
        
        self._update_list_item_text_format(self.list_file.viewMode())
        
        if self.list_file.count() == 1:
            self.list_file.setCurrentRow(0)
            self.load_selected_file(item)
    
    def update_ui_from_thread(self, original_path, temp_original_path, label_data, w, h):
        """Cập nhật UI từ luồng xử lý ảnh đơn/screenshot."""
        file_name = os.path.basename(original_path)
        
        self.file_id_counter += 1
        self.file_metadata[original_path] = {
            'type': 'image', 
            'original_path': temp_original_path, 
            'label_data': label_data, 
            'id': self.file_id_counter,
            'save_status': False,
            'width': w,
            'height': h
        }

        q_image = self._draw_boxes_on_image(temp_original_path, label_data)
        if q_image:
            self.main_viewer.set_image(q_image)
            self.label_size.setText(f"Kích thước: {q_image.width()}x{q_image.height()}")
        
        formatted_name = self._format_filename(original_path, max_len=50)
        self.label_filename.setText(f"Tên file: {formatted_name}")
        self.current_image_path = original_path
        
        is_new_file = file_name not in self.listed_file_names

        if is_new_file: 
             current_view_mode = self.list_file.viewMode()
             is_detail_view = (current_view_mode == QListWidget.ListMode and self.list_file.gridSize().height() <= 30)

             if is_detail_view:
                 icon = QIcon(self.icon_image_default)
             else:
                 temp_pixmap = QPixmap()
                 if temp_pixmap.load(temp_original_path):
                     icon = QIcon(temp_pixmap)
                 else:
                     icon = QIcon(self.icon_image_default)
             
             item = QListWidgetItem(icon, self._format_filename(original_path, max_len=20))
             item.setToolTip(original_path) 
             
             self.list_file.insertItem(0, item)
             self.file_list.insert(0, original_path)
             self.listed_file_names.add(file_name) 

        self.reset_save_button() 
        self.list_file.setCurrentRow(0) 
        self.btn_clear.setEnabled(True)
        
        self._update_list_item_text_format(self.list_file.viewMode())
        
        if self.auto_save:
            self._auto_save_current_image()

    def _handle_video_processed(self, original_path, result_path, thumbnail_path, w, h):
        """Được gọi khi VideoWorker hoàn thành."""
        self._stop_video_playback() 
        file_name = os.path.basename(original_path)
        
        self.file_id_counter += 1
        self.file_metadata[original_path] = {
            'type': 'video', 
            'result_path': result_path, 
            'thumbnail_path': thumbnail_path,
            'id': self.file_id_counter,
            'save_status': False,
            'width': w,
            'height': h
        }
        
        if original_path not in self.file_list:
            self.file_list.insert(0, original_path)
            self.listed_file_names.add(file_name) 
        
        current_view_mode = self.list_file.viewMode()
        is_detail_view = (current_view_mode == QListWidget.ListMode and self.list_file.gridSize().height() <= 30)

        if is_detail_view:
            icon = QIcon(self.icon_video_default)
        else:
            icon = QIcon(thumbnail_path) if thumbnail_path else QIcon(self.icon_video_default)
        
        text = self._format_filename(original_path, max_len=20)
        if not text.endswith("(V)"):
            text += " (V)"
            
        item = QListWidgetItem(icon, text)
        item.setToolTip(original_path) 
        
        for i in range(self.list_file.count()):
            list_item = self.list_file.item(i)
            if list_item.toolTip() == original_path:
                self.list_file.takeItem(i)
                break
        
        self.list_file.insertItem(0, item)
        self.list_file.setCurrentRow(0)
        self.btn_clear.setEnabled(True)
        
        self._update_list_item_text_format(self.list_file.viewMode())
        
        self.load_selected_file(item)
        self.show_status_message(f"✅ Video {file_name} đã xử lý xong và sẵn sàng phát.", 5000)

    def load_selected_file(self, item):
        """Tải ảnh/video khi click."""
        if not item:
            return
            
        full_path_original = item.toolTip()
        
        if full_path_original:
            self.current_image_path = full_path_original
            self._stop_video_playback()
            self.main_viewer.clear_view() # Hàm này đã reset cờ user_has_zoomed
            
            metadata = self.file_metadata.get(full_path_original)
            
            if metadata and metadata['type'] == 'video':
                self.video_controls_widget.setVisible(True) 
                
                result_path = metadata['result_path']
                if os.path.exists(result_path):
                    self._start_video_playback(result_path)
                    
                    formatted_name = self._format_filename(full_path_original, max_len=50)
                    self.label_filename.setText(f"Video: {formatted_name}")
                    
                    self.label_size.setText(f"Kích thước: {metadata.get('width', 0)}x{metadata.get('height', 0)}")
                    self.reset_save_button(is_video=True, saved=metadata.get('save_status', False))
                else:
                    self.show_status_message("Không tìm thấy video kết quả đã xử lý.", 5000)

            elif metadata and metadata['type'] == 'image':
                self.video_controls_widget.setVisible(False) 
                
                label_data = metadata.get('label_data')
                if not label_data:
                    label_path = metadata.get('label_path', '')
                    if os.path.exists(label_path):
                         label_data = []
                         with open(label_path, 'r') as f:
                             for line in f:
                                parts = line.strip().split()
                                if len(parts) >= 6:
                                    try:
                                        label_data.append([int(parts[0]), float(parts[1]), float(parts[2]), float(parts[3]), float(parts[4]), float(parts[5])])
                                    except ValueError:
                                        pass
                         metadata['label_data'] = label_data

                q_image = self._draw_boxes_on_image(metadata['original_path'], label_data)
                
                if q_image:
                    self.main_viewer.set_image(q_image) # Hàm này đã reset cờ user_has_zoomed
                    
                    formatted_name = self._format_filename(full_path_original, max_len=50)
                    self.label_filename.setText(f"Tên file: {formatted_name}")
                    
                    self.label_size.setText(f"Kích thước: {q_image.width()}x{q_image.height()}")
                    
                    self.reset_save_button(is_video=False, saved=metadata.get('save_status', False))
                    
                    if self.auto_save:
                        self._auto_save_current_image()
                        
                else:
                    self.show_status_message("Không tìm thấy file ảnh gốc tạm thời.", 5000)
            
            else:
                 self.show_status_message(f"Lỗi: Không tìm thấy Metadata cho file {os.path.basename(full_path_original)}.", 5000)
    
    def _get_color_for_class(self, class_id):
        """Lấy màu ngẫu nhiên (hoặc định sẵn) cho class."""
        if class_id not in self.class_colors:
            self.class_colors[class_id] = [random.randint(0, 255) for _ in range(3)]
        return self.class_colors[class_id]

    def _draw_boxes_on_image(self, image_path, label_data):
        """Đọc ảnh gốc và file nhãn, sau đó vẽ box dựa trên cờ Show/Hide."""
        if not os.path.exists(image_path):
            self.show_status_message(f"Thiếu file tạm: {os.path.basename(image_path)}", 3000)
            return None
            
        img_np = cv2.imread(image_path)
        if img_np is None:
            self.show_status_message(f"Lỗi đọc ảnh: {os.path.basename(image_path)}", 3000)
            return None
            
        h, w, _ = img_np.shape

        if self.is_box_visible:
            if not label_data and self.current_image_path:
                 metadata = self.file_metadata.get(self.current_image_path, {})
                 label_path = metadata.get('label_path', '')
                 if os.path.exists(label_path):
                    label_data =[]
                    with open(label_path, 'r') as f:
                         for line in f:
                            parts = line.strip().split()
                            if len(parts) >= 6:
                                try:
                                    label_data.append([int(parts[0]), float(parts[1]), float(parts[2]), float(parts[3]), float(parts[4]), float(parts[5])])
                                except ValueError:
                                    pass
                    metadata['label_data'] = label_data

            if label_data:
                for (class_id, x_c, y_c, b_w, b_h, conf) in label_data:
                    x_center = x_c * w
                    y_center = y_c * h
                    box_w = b_w * w
                    box_h = b_h * h
                    
                    x1 = int(x_center - box_w / 2)
                    y1 = int(y_center - box_h / 2)
                    x2 = int(x_center + box_w / 2)
                    y2 = int(y_center + box_h / 2)
                    
                    color = self._get_color_for_class(class_id)
                    
                    cv2.rectangle(img_np, (x1, y1), (x2, y2), color, 2)
                    
                    if self.is_class_visible:
                        label = f"{self.class_names.get(class_id, 'Unknown')}"
                        
                        if self.is_confidence_visible:
                            label += f" {conf:.2f}"
                        
                        (text_w, text_h), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 2)
                        
                        margin = 10 
                        
                        rect_y_top = y1 - text_h - (baseline + 3)
                        text_y_pos = y1 - (baseline // 2) - 3

                        if rect_y_top < margin:
                            rect_y_top = y2 + 3
                            text_y_pos = y2 + text_h + 3
                            
                            if text_y_pos + baseline > h - margin:
                                rect_y_top = y1 + 3
                                text_y_pos = y1 + text_h + 3
                        
                        cv2.rectangle(img_np, (x1, rect_y_top), (x1 + text_w, text_y_pos + baseline), color, -1)
                        cv2.putText(img_np, label, (x1, text_y_pos), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,0,0), 2) 

                        
        img_np_rgb = cv2.cvtColor(img_np, cv2.COLOR_BGR2RGB)
        height_q, width_q, channel = img_np_rgb.shape
        bytes_per_line = 3 * width_q
        q_image = QImage(img_np_rgb.data, width_q, height_q, bytes_per_line, QImage.Format_RGB888)
        return q_image

    # --- Các hàm Xử lý Sự kiện UI ---

    def import_image(self):
        if not self.model:
            self.show_status_message("Vui lòng load model trước.", 3000)
            return
        path, _ = QFileDialog.getOpenFileName(self, "Chọn file ảnh", "", "Image Files (*.jpg *.jpeg *.png)")
        if path:
            self.run_prediction_worker(path, is_batch=False)
            
    def import_video(self):
        if not self.model:
            self.show_status_message("Vui lòng load model trước.", 3000)
            return
        path, _ = QFileDialog.getOpenFileName(self, "Chọn file video", "", "Video Files (*.mp4 *.avi *.mov)")
        if path:
            self.run_video_prediction(path)

    def run_video_prediction(self, video_path):
        """Hàm chung để xử lý một file video, dù là import hay quay màn hình."""
        if not self.model:
            self.show_status_message("Vui lòng load model trước.", 3000)
            return
            
        self.show_status_message(f"Đang xử lý video {os.path.basename(video_path)}. Vui lòng chờ...", 0) 
        
        for i in range(self.list_file.count()):
            item = self.list_file.item(i)
            if item.toolTip() == video_path:
                self.list_file.takeItem(i)
                break
        
        placeholder_item = QListWidgetItem(QIcon.fromTheme("video-x-generic"), os.path.basename(video_path) + " (Đang xử lý...)")
        placeholder_item.setToolTip(video_path)
        self.list_file.insertItem(0, placeholder_item)
        self.list_file.setCurrentItem(placeholder_item)

        worker = VideoWorker(self.model, video_path, self.temp_dir)
        worker.signals.video_processed.connect(self._handle_video_processed)
        worker.signals.error.connect(lambda msg: self.show_status_message(f"LỖI VIDEO: {msg}", 8000))
        self.threadpool.start(worker)

    def load_folder(self):
        if not self.model:
            self.show_status_message("Vui lòng load model trước.", 3000)
            return

        folder_path = QFileDialog.getExistingDirectory(self, "Chọn thư mục chứa ảnh")
        if not folder_path:
            return
        
        supported_extensions = ('.jpg', '.jpeg', '.png')
        
        file_paths = []
        for filename in os.listdir(folder_path):
            if filename.lower().endswith(supported_extensions):
                file_paths.append(os.path.join(folder_path, filename))

        if not file_paths:
            self.show_status_message("Không tìm thấy file ảnh hợp lệ.", 3000)
            return
            
        self.run_prediction_worker(file_paths, is_batch=True)
        self.show_status_message(f"Đang chạy nền xử lý {len(file_paths)} file ảnh. UI vẫn hoạt động.", 5000)

    def _prepare_screenshot_tool(self):
        """Helper: Ẩn cửa sổ chính và chụp desktop."""
        if not self.model:
            self.show_status_message("Vui lòng load model trước.", 3000)
            return None
        
        screen = QApplication.primaryScreen()
        if not screen:
            self.show_status_message("Lỗi: Không thể truy cập màn hình.", 5000)
            return None
        
        self.set_transparent_mode(True)
        QCoreApplication.processEvents()
        time.sleep(0.2) 
        
        desktop_id = QApplication.desktop().winId() 
        desktop_pixmap = screen.grabWindow(desktop_id)
        
        if desktop_pixmap.isNull():
            self.show_status_message("Lỗi: Không thể chụp ảnh màn hình.", 5000)
            self.set_transparent_mode(False) # KHÔI PHỤC nếu có lỗi
            return None
             
        return desktop_pixmap

    def process_screenshot(self):
        """Chụp ảnh màn hình (Screenshot)."""
        desktop_pixmap = self._prepare_screenshot_tool()
        if desktop_pixmap:
            self.screenshot_tool.start_snip(desktop_pixmap, is_recording=False)

    def process_screen_recording(self):
        """Quay video màn hình (Screen Recording)."""
        desktop_pixmap = self._prepare_screenshot_tool()
        if desktop_pixmap:
            self.screenshot_tool.start_snip(desktop_pixmap, is_recording=True)

    def start_recording_worker(self, rect):
        """Bắt đầu worker quay video khi ScreenshotTool phát tín hiệu."""
        if self.current_recorder:
            self.show_status_message("Đã có một tiến trình quay đang chạy.", 3000)
            return
            
        self.show_status_message("Bắt đầu quay! Nhấn 'Esc' để dừng...", 0)
        self.current_recorder = RecordingWorker(rect, self.temp_dir)
        
        self.current_recorder.signals.recording_finished.connect(self.handle_recording_finished)
        self.current_recorder.signals.error.connect(lambda msg: self.show_status_message(f"Lỗi Quay Video: {msg}", 8000))
        
        self.threadpool.start(self.current_recorder)

    def handle_recording_finished(self, video_path):
        """Xử lý file video sau khi quay xong."""
        self.current_recorder = None
        self.set_transparent_mode(False)
        self.show_status_message(f"Đã quay xong: {os.path.basename(video_path)}. Bắt đầu xử lý...", 3000)
        self.run_video_prediction(video_path)

    def run_screenshot_prediction(self, snipped_pixmap: QPixmap):
        """Xử lý QPixmap nhận được từ ScreenshotTool (chụp ảnh)."""
        self.setCursor(QCursor(Qt.ArrowCursor))
        self.showNormal()
        self.activateWindow()
        self.set_transparent_mode(False)

        if snipped_pixmap.width() <= 5 or snipped_pixmap.height() <= 5:
            self.show_status_message("Vùng chọn quá nhỏ hoặc không hợp lệ.", 3000)
            return

        try:
            temp_file_path = os.path.join(self.temp_dir, f"screenshot_{int(time.time())}.png")
            
            if not snipped_pixmap.save(temp_file_path, "PNG"):
                raise Exception("Không thể lưu QPixmap.")
            
            self.run_prediction_worker(temp_file_path, is_batch=False)
            
        except Exception as e:
            self.show_status_message(f"Lỗi Chụp Ảnh: {e}", 5000)
            self.showNormal()
            
    def run_prediction_worker(self, file_paths, is_batch=False):
        """Khởi tạo và chạy PredictionWorker trên threadpool."""
        if not self.model:
            self.show_status_message("Lỗi: Model chưa được load.", 5000)
            return

        worker = PredictionWorker(self.model, file_paths, self.temp_dir, is_batch)
        
        if is_batch:
            worker.signals.file_processed.connect(self.add_file_to_list)
            worker.signals.finished.connect(lambda: self.show_status_message(f"Hoàn tất xử lý {len(file_paths)} ảnh.", 3000))
        else:
            worker.signals.result.connect(self.update_ui_from_thread)

        worker.signals.error.connect(lambda msg: self.show_status_message(f"LỖI WORKER: {msg}", 8000))
        
        self.threadpool.start(worker)

    # --- Các hàm UI khác ---

    def _mark_save_success(self, file_path):
        if file_path:
            if file_path in self.file_metadata:
                self.file_metadata[file_path]['save_status'] = True
        
        self.btn_save.setText("Save ✓")
        default_style = self.btn_clear.styleSheet() 
        self.btn_save.setStyleSheet(default_style + "background-color: #A5D6A7; color: #388E3C;")
        self.btn_save.setDisabled(True)

    def _save_image_to_path(self, save_path, pixmap):
        if pixmap is None:
            return False
        try:
            pixmap.save(save_path)
            self.show_status_message(f"Đã lưu thành công tại: {os.path.basename(save_path)}", 5000)
            self._mark_save_success(self.current_image_path) 
            return True
        except Exception as e:
            self.show_status_message(f"Lỗi khi lưu file: {e}", 5000)
            return False

    def save_result(self):
        if not self.current_image_path:
            self.show_status_message("Chưa có ảnh/video nào để lưu.", 3000)
            return

        metadata = self.file_metadata.get(self.current_image_path)
        if not metadata:
             self.show_status_message("Lỗi: Không tìm thấy metadata.", 3000)
             return
             
        if metadata and metadata['type'] == 'video':
             result_path = metadata.get('result_path')
             if not result_path or not os.path.exists(result_path):
                 self.show_status_message("Video kết quả chưa được xử lý hoặc đã bị xóa.", 5000)
                 return
             
             current_file_name = os.path.basename(self.current_image_path)
             default_name = current_file_name.replace('.', '_processed.')

             save_path, _ = QFileDialog.getSaveFileName(self, "Lưu Video Kết quả", 
                                                        os.path.join(self.export_location or QDir.currentPath(), default_name), 
                                                        "Video Files (*.mp4 *.avi)")
            
             if not save_path: 
                self.show_status_message("Hủy lưu file.", 3000)
                return

             try:
                shutil.copy(result_path, save_path)
                self.export_location = os.path.dirname(save_path)
                self.show_status_message(f"Đã lưu video thành công tại: {os.path.basename(save_path)}", 5000)
                self._mark_save_success(self.current_image_path)
             except Exception as e:
                 self.show_status_message(f"Lỗi khi copy video: {e}", 5000)
                 
        elif metadata and metadata['type'] == 'image':
            if self.main_viewer.current_pixmap is None:
                 self.show_status_message("Không thể lưu: Ảnh hiển thị không tồn tại.", 3000)
                 return
                 
            current_file_name = os.path.basename(self.current_image_path)
            default_name = current_file_name.replace('.', '_processed.')
            
            save_path, _ = QFileDialog.getSaveFileName(self, "Lưu kết quả nhận diện", 
                                                        os.path.join(self.export_location or QDir.currentPath(), default_name), 
                                                        "Image Files (*.jpg *.png)")
            
            if not save_path: 
                self.show_status_message("Hủy lưu file.", 3000)
                return
            
            self.export_location = os.path.dirname(save_path)
            self._save_image_to_path(save_path, self.main_viewer.current_pixmap)
        
    def reset_save_button(self, is_video=False, saved=False):
        self.btn_save.setStyleSheet(self.widget_styles.get(self.btn_save, ""))
        
        if saved:
            self._mark_save_success(self.current_image_path)
            return

        if is_video:
            self.btn_save.setText("Save Video")
            self.btn_save.setDisabled(False) 
        else:
            self.btn_save.setText("Save")
            self.btn_save.setDisabled(False)

    def clear_all(self, ask_confirm=True):
        if ask_confirm:
            reply = QMessageBox.question(self, 'Xác nhận', 
                                        "Bạn có chắc muốn xóa rỗng cửa sổ và File List không?",
                                        QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
            if reply == QMessageBox.No:
                return
        
        self._stop_video_playback()
        self.main_viewer.clear_view() # Hàm này đã reset cờ user_has_zoomed
        self.list_file.clear()
        self.file_list = []
        self.save_status = {}
        self.listed_file_names = set() 
        self.file_metadata = {} 
        self.current_image_path = None
        self.label_filename.setText("Tên file: (Chưa có ảnh)")
        self.label_size.setText("Kích thước: N/A")
        self.reset_save_button()
        self.video_controls_widget.setVisible(False)
        self.btn_clear.setEnabled(False)
        self.show_status_message("Đã xóa rỗng giao diện.", 3000)

    # --- Các hàm Menu ---
    
    def _auto_save_current_image(self):
        """Hàm helper để tự động lưu ảnh/video hiện tại."""
        if (not self.current_image_path) or (not self.export_location) or (self.export_location == QDir.currentPath()):
            return False
            
        metadata = self.file_metadata.get(self.current_image_path)
        if not metadata or metadata.get('save_status', False):
            return False

        self.show_status_message(f"Auto-saving {os.path.basename(self.current_image_path)}...", 2000)
        
        current_file_name = os.path.basename(self.current_image_path)
        
        if metadata['type'] == 'video':
            result_path = metadata.get('result_path')
            if not result_path or not os.path.exists(result_path):
                 self.show_status_message("Lỗi auto-save: Video kết quả không tồn tại.", 5000)
                 return
            default_name = current_file_name.replace('.', '_processed.')
            save_path = os.path.join(self.export_location, default_name)
            try:
                shutil.copy(result_path, save_path)
                self._mark_save_success(self.current_image_path)
            except Exception as e:
                 self.show_status_message(f"Lỗi auto-save video: {e}", 5000)
                 
        elif metadata['type'] == 'image':
            if self.main_viewer.current_pixmap is None:
                q_image = self._draw_boxes_on_image(metadata['original_path'], metadata.get('label_data'))
                if q_image:
                     pixmap_to_save = QPixmap.fromImage(q_image)
                else:
                    return False
            else:
                pixmap_to_save = self.main_viewer.current_pixmap

            default_name = current_file_name.replace('.', '_processed.')
            save_path = os.path.join(self.export_location, default_name)
            self._save_image_to_path(save_path, pixmap_to_save)


    def toggle_autosave(self):
        self.auto_save = not self.auto_save
        status = "ON" if self.auto_save else "OFF"
        
        if self.auto_save:
            if not self.export_location or self.export_location == QDir.currentPath():
                self.show_status_message("Auto-save yêu cầu chọn thư mục Export...", 2000)
                if not self.choose_export_location():
                    self.show_status_message("Hủy Auto-save: Chưa chọn thư mục Export.", 3000)
                    self.auto_save = False
                    self.act_autosave.setChecked(False)
                    return
            
            self.show_status_message("Auto-save đã BẬT.", 3000)
            self._auto_save_current_image()

        else:
            self.show_status_message("Auto-save đã TẮT.", 3000)

        self.act_autosave.setChecked(self.auto_save)
        self.act_autosave.setText(f"Auto-save result ({'ON' if self.auto_save else 'OFF'})")

    
    def choose_export_location(self):
        """Trả về True nếu chọn thành công, False nếu hủy."""
        folder_path = QFileDialog.getExistingDirectory(self, "Chọn thư mục xuất kết quả")
        if folder_path:
            self.export_location = folder_path
            self.show_status_message(f"Vị trí xuất mặc định: {folder_path}", 3000)
            
            if self.auto_save:
                self._auto_save_current_image()
            return True
        return False

    def toggle_bounding_box(self):
        self.is_box_visible = not self.is_box_visible
        status = "ON" if self.is_box_visible else "OFF"
        self.act_show_hide_box.setText(f"Show/Hide Boundingbox ({status})")
        
        if not self.is_box_visible:
            self.act_show_hide_class.setChecked(False) 
            self.is_class_visible = False
            self.act_show_hide_class.setText(f"Show/hide Class Name (OFF)")
            
            self.act_show_hide_conf.setChecked(False)
            self.is_confidence_visible = False
            self.act_show_hide_conf.setText(f"Show/Hide Confidence (OFF)")
        
        self._redraw_current_image()

    def toggle_class_name(self):
        self.is_class_visible = not self.is_class_visible
        status = "ON" if self.is_class_visible else "OFF"
        self.act_show_hide_class.setText(f"Show/hide Class Name ({status})")
        
        if self.is_class_visible:
            self.act_show_hide_box.setChecked(True) 
            self.is_box_visible = True
            self.act_show_hide_box.setText(f"Show/Hide Boundingbox (ON)")
        else:
            self.act_show_hide_conf.setChecked(False)
            self.is_confidence_visible = False
            self.act_show_hide_conf.setText(f"Show/Hide Confidence (OFF)")

        self._redraw_current_image()

    def toggle_confidence(self):
        self.is_confidence_visible = not self.is_confidence_visible
        status = "ON" if self.is_confidence_visible else "OFF"
        self.act_show_hide_conf.setText(f"Show/Hide Confidence ({status})")
        
        if self.is_confidence_visible:
            self.act_show_hide_box.setChecked(True) 
            self.is_box_visible = True
            self.act_show_hide_box.setText(f"Show/Hide Boundingbox (ON)")
            
            self.act_show_hide_class.setChecked(True) 
            self.is_class_visible = True
            self.act_show_hide_class.setText(f"Show/hide Class Name (ON)")
            
        self._redraw_current_image()

    def _redraw_current_image(self):
        """Hàm helper để vẽ lại ảnh hiện tại khi thay đổi cờ Show/Hide."""
        if self.current_image_path and self.file_metadata.get(self.current_image_path, {}).get('type') == 'image':
            current_item = self.list_file.currentItem()
            if current_item:
                # Khi vẽ lại, chúng ta muốn nó fit
                self.main_viewer.user_has_zoomed = False
                self.load_selected_file(current_item)

    def import_model(self):
        path, _ = QFileDialog.getOpenFileName(self, "Chọn model YOLO", "", "YOLO model (*.pt)")
        if path:
            try:
                self.model = YOLO(path)
                self.class_names = self.model.names
                self.class_colors = {i: [random.randint(100, 255) for _ in range(3)] for i in self.class_names.keys()}
                
                self.show_status_message(f"✅ Model đã load: {os.path.basename(path)}", 5000)
                
                style_check = f"""
                    QToolButton {{
                        border-image: url("{self.icon_path_model_check}") 0 0 0 0 stretch stretch;
                        border: 1px solid #888;
                    }}
                """
                self.btn_import_model.setStyleSheet(style_check)
                self.widget_styles[self.btn_import_model] = style_check
                
                self._set_controls_enabled(True)
                
            except Exception as e:
                self.show_status_message(f"Lỗi: Không load được model: {e}", 5000)
                self.model = None
                
                style_model = f"""
                    QToolButton {{
                        border-image: url("{self.icon_path_model}") 0 0 0 0 stretch stretch;
                        border: 1px solid #888;
                    }}
                """
                self.btn_import_model.setStyleSheet(style_model)
                self.widget_styles[self.btn_import_model] = style_model
                
                self._set_controls_enabled(False)
            
    # --- Listener phím (cho 'Esc') ---
    
    def on_press(self, key):
        """Hàm callback khi nhấn phím (cho pynput.Listener)."""
        try:
            if key == Key.esc and self.current_recorder:
                self.current_recorder.stop()
                # Gửi tín hiệu về main thread để cập nhật UI
                QTimer.singleShot(0, lambda: self.show_status_message("Đã dừng quay.", 3000))
        except Exception as e:
            print(f"Lỗi listener: {e}")

    def start_key_listener(self):
        """Khởi động pynput listener trong một luồng riêng."""
        if not Listener:
            print("Không thể khởi động Key Listener (thiếu pynput).")
            return
            
        def run_listener():
            try:
                with Listener(on_press=self.on_press) as listener:
                    self.key_listener = listener
                    listener.join()
            except Exception as e:
                # Có thể xảy ra lỗi nếu môi trường không hỗ trợ (VD: Wayland)
                print(f"Không thể khởi động pynput listener: {e}")
                self.key_listener = None

        listener_thread = QThread()
        listener_thread.run = run_listener
        listener_thread.daemon = True # Thoát khi chương trình chính thoát
        listener_thread.start()
        self.listener_thread = listener_thread # Giữ tham chiếu

    def keyPressEvent(self, event):
        """Xử lý phím tắt cho cửa sổ chính (A, D, Q, S)."""
        key = event.key()
        
        # Bỏ qua nếu đang nhập text
        if self.focusWidget() and isinstance(self.focusWidget(), (QLabel, QSlider, QLineEdit)):
             super().keyPressEvent(event)
             return
        
        # 'Esc' đã được pynput xử lý toàn cục
        
        if key == Qt.Key_A:
            self.previous_image()
        elif key == Qt.Key_D:
            self.next_image()
        elif key == Qt.Key_Q:
            if self.btn_import_model.isEnabled():
                self.import_model()
        elif key == Qt.Key_S:
            if self.btn_save.isEnabled():
                self.save_result()
        else:
            super().keyPressEvent(event)
            
    def next_image(self):
        current_row = self.list_file.currentRow()
        if current_row == -1 and self.list_file.count() > 0: 
            self.list_file.setCurrentRow(0)
            self.load_selected_file(self.list_file.item(0))
        elif current_row < self.list_file.count() - 1:
            next_item = self.list_file.item(current_row + 1)
            self.list_file.setCurrentItem(next_item)
            self.load_selected_file(next_item)
        else:
            self.show_status_message("Đã đến cuối danh sách.", 2000)

    def previous_image(self):
        current_row = self.list_file.currentRow()
        if current_row > 0:
            previous_item = self.list_file.item(current_row - 1)
            self.list_file.setCurrentItem(previous_item)
            self.load_selected_file(previous_item)
        elif current_row == 0:
            self.show_status_message("Đã ở đầu danh sách.", 2000)
        else:
            pass
            
    def fit_to_view(self):
        if self.main_viewer.current_pixmap_item:
            self.main_viewer.resetTransform()
            
            # === SỬA LỖI FIT TO VIEW: Reset cờ zoom ===
            self.main_viewer.user_has_zoomed = False
            
            self.main_viewer.fitInView(self.main_viewer.scene.sceneRect(), Qt.KeepAspectRatio)
        else:
            self.show_status_message("Chưa có ảnh để căn chỉnh.", 2000)

if __name__ == "__main__":
    app = QCoreApplication.instance()
    if app is None:
        app = QApplication(sys.argv)
        
    window = VehicleDetectorGUI()
    window.show()
    sys.exit(app.exec())