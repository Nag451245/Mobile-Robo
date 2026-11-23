// Libraries for Sweety's avatar page, bundled into one file for the Android WebView:
// three.js (MIT) as the global THREE that blink.js (robot Sweety) expects, and three-vrm (MIT) for VRM characters.
import * as THREE_NS from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';

window.THREE = Object.assign({}, THREE_NS);
window.VRMKit = { GLTFLoader, VRMLoaderPlugin, VRMUtils };
