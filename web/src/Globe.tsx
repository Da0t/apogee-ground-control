import React, { useState } from "react";
import { RotateCcw, ChevronLeft, ChevronRight } from "lucide-react";
import type { ContactStatus } from "./types";

const rad = Math.PI / 180;
type Point = [number, number];
// Public-domain Natural Earth 1:110m coastlines, bundled for offline use.
import coastlines from "./land.json";
const land = coastlines as Point[][];
export function Globe({ contact }: { contact: ContactStatus }) {
  const [rotation, setRotation] = useState(contact.plan.longitude);
  const [drag, setDrag] = useState<{ x: number; rotation: number } | null>(
    null,
  );
  const latitude = 20 * rad;
  const project = ([lon, lat]: Point) => {
    const phi = lat * rad,
      theta = (lon - rotation) * rad;
    return {
      x: 240 + 174 * Math.cos(phi) * Math.sin(theta),
      y:
        205 -
        174 *
          (Math.cos(latitude) * Math.sin(phi) -
            Math.sin(latitude) * Math.cos(phi) * Math.cos(theta)),
      front:
        Math.sin(latitude) * Math.sin(phi) +
          Math.cos(latitude) * Math.cos(phi) * Math.cos(theta) >=
        0,
    };
  };
  function path(points: Point[]) {
    let drawing = false;
    return points
      .map((point) => {
        const p = project(point);
        if (!p.front) {
          drawing = false;
          return "";
        }
        const command = drawing ? "L" : "M";
        drawing = true;
        return `${command}${p.x.toFixed(1)},${p.y.toFixed(1)}`;
      })
      .join(" ");
  }
  const lat = contact.plan.latitude * rad,
    lon = contact.plan.longitude * rad,
    angle = contact.phase * 2 * Math.PI;
  const station = [
    Math.cos(lat) * Math.cos(lon),
    Math.cos(lat) * Math.sin(lon),
    Math.sin(lat),
  ];
  const east = [-Math.sin(lon), Math.cos(lon), 0];
  const orbital = (phase: number): Point => {
    const p = station.map(
      (v, i) => v * Math.cos(phase) + east[i] * Math.sin(phase),
    );
    return [Math.atan2(p[1], p[0]) / rad, Math.asin(p[2]) / rad];
  };
  const position = orbital(angle),
    craft = project(position),
    ground = project([contact.plan.longitude, contact.plan.latitude]);
  return (
    <div className="globe-frame">
      <svg
        viewBox="0 0 480 420"
        className="earth-globe"
        role="img"
        aria-label="Interactive schematic globe with simulated spacecraft and ground station"
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          setDrag({ x: e.clientX, rotation });
        }}
        onPointerMove={(e) => {
          if (drag) setRotation(drag.rotation - (e.clientX - drag.x) * 0.5);
        }}
        onPointerUp={() => setDrag(null)}
        onPointerCancel={() => setDrag(null)}
      >
        <defs>
          <radialGradient id="earth-light" cx="35%" cy="28%">
            <stop stopColor="#262626" />
            <stop offset="1" stopColor="#101010" />
          </radialGradient>
        </defs>
        <circle
          cx="240"
          cy="205"
          r="187"
          fill="none"
          stroke="#252525"
          strokeDasharray="2 8"
        />
        <circle
          cx="240"
          cy="205"
          r="174"
          fill="url(#earth-light)"
          stroke="#444"
        />
        {[-60, -30, 0, 30, 60].map((lat) => (
          <path
            key={`lat${lat}`}
            d={path(
              Array.from(
                { length: 145 },
                (_, i) => [-180 + i * 2.5, lat] as Point,
              ),
            )}
            fill="none"
            stroke="#343434"
            strokeWidth=".7"
          />
        ))}
        {Array.from({ length: 12 }, (_, i) => i * 30 - 180).map((lon) => (
          <path
            key={`lon${lon}`}
            d={path(
              Array.from(
                { length: 73 },
                (_, i) => [lon, -90 + i * 2.5] as Point,
              ),
            )}
            fill="none"
            stroke="#343434"
            strokeWidth=".7"
          />
        ))}
        {land.map((outline, i) => (
          <path
            key={i}
            d={path(outline)}
            fill="none"
            stroke="#686868"
            strokeWidth="1.2"
            strokeLinejoin="round"
          />
        ))}
        <path
          d={path(
            Array.from({ length: 181 }, (_, i) => orbital((i * Math.PI) / 90)),
          )}
          stroke="#a0a0a0"
          strokeWidth="1.2"
          fill="none"
          strokeDasharray="4 5"
        />
        {ground.front && (
          <g>
            <circle
              cx={ground.x}
              cy={ground.y}
              r="6"
              fill="#111"
              stroke="#fff"
            />
            <circle cx={ground.x} cy={ground.y} r="2" fill="#fff" />
            <text
              x={ground.x + 12}
              y={ground.y + 4}
              fill="#d7d7d7"
              fontSize="10"
              fontFamily="monospace"
            >
              {contact.plan.station}
            </text>
          </g>
        )}
        {craft.front && (
          <g>
            <circle cx={craft.x} cy={craft.y} r="12" fill="#ffffff10" />
            <path
              d={`M${craft.x - 8},${craft.y}H${craft.x + 8}M${craft.x},${craft.y - 8}V${craft.y + 8}`}
              stroke="#fff"
              strokeWidth="2"
            />
            <rect
              x={craft.x - 3}
              y={craft.y - 3}
              width="6"
              height="6"
              fill="#fff"
            />
            <text
              x={craft.x + 13}
              y={craft.y - 10}
              fill="#fff"
              fontFamily="monospace"
              fontSize="9"
            >
              ASTER-01
            </text>
          </g>
        )}
        <text
          x="240"
          y="410"
          textAnchor="middle"
          fill="#858585"
          fontFamily="monospace"
          fontSize="8"
        >
          SCHEMATIC EARTH · SYNTHETIC SURFACE TRACK
        </text>
      </svg>
      <div className="globe-controls">
        <span>
          {position[1].toFixed(1)}° lat / {position[0].toFixed(1)}° lon
          <br />
          <small>Illustrative position · drag to rotate</small>
        </span>
        <div>
          <button
            aria-label="Rotate globe left"
            onClick={() => setRotation(rotation - 30)}
          >
            <ChevronLeft size={14} />
          </button>
          <button
            aria-label="Center globe on station"
            onClick={() => setRotation(contact.plan.longitude)}
          >
            <RotateCcw size={14} />
          </button>
          <button
            aria-label="Rotate globe right"
            onClick={() => setRotation(rotation + 30)}
          >
            <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
