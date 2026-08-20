# Radio Operations Exam OMR Scanner 📝

An Android-based **Optical Mark Recognition (OMR)** app that automates the scoring of Radio Operations Examination answer sheets using computer vision. Built with **Kotlin** and **OpenCV**, the app detects shaded answer bubbles from a scanned or photographed answer sheet and computes scores automatically — cutting manual checking time significantly.

## ✨ Key Features

- 📷 **Automated Answer Detection** — Uses OpenCV to detect shaded/marked answers on Radio Operations Examination sheets with **95% detection accuracy**.
- ⚡ **Faster Scoring** — Reduces manual exam-checking time by **60%** compared to hand-grading.
- 📱 **Native Android App** — Built entirely in Kotlin using Android Studio, designed for on-device use (no server/backend required).
- 📄 **Documented Codebase** — Fully documented code and handoff documentation to support continued maintenance and future algorithm improvements.

## 🛠️ Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| IDE | Android Studio |
| Computer Vision | OpenCV (Android SDK) |
| Platform | Android |

## 📸 How It Works

1. **Capture/Import** — The user captures a photo of a completed answer sheet (or imports an existing image) using the device camera.
2. **Preprocessing** — The image is converted to grayscale, thresholded, and corrected for perspective/skew using OpenCV.
3. **Bubble Detection** — Contour detection identifies each answer bubble region on the sheet.
4. **Mark Classification** — Each bubble is analyzed for pixel fill/shading ratio to determine whether it was marked.
5. **Scoring** — Detected answers are compared against an answer key, and a score is generated automatically.

## 📊 Results

- **60%** improvement in exam-checking speed vs. manual grading.
- **95%** detection accuracy for shaded answer marks.

## 🗺️ Roadmap / Future Improvements

- [ ] Improve detection robustness under poor lighting conditions
- [ ] Support additional exam sheet formats
- [ ] Add batch scanning mode
- [ ] Export results to CSV/Excel

## 👤 Author

**KC**
- GitHub: https://github.com/seollemi
