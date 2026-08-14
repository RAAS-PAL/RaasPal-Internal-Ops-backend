#!/usr/bin/env bash
# Generates idempotent SQL to seed robots + robot_specs_cleaning from the
# decoded Gausium datasheet (decoded.txt: "row|colLetter|value").
set -euo pipefail

SP="$(cd "$(dirname "$0")" && pwd)"
DEC="$SP/xlsx/decoded.txt"
OUT="$SP/seed_gausium_specs.sql"

# Excel column letters C..CY in order, paired with DB column + type.
# Types: N = numeric, T = text, Y = Yes/No boolean, B = 1/0 boolean
MAP=$(cat <<'EOF'
dimension_l_mm N
dimension_w_mm N
dimension_h_mm N
robot_weight_kg N
sweep_width_mm N
scrub_width_mm N
mop_width_mm N
brush_pressure_kg N
roller_speed_rpm N
brush_speed_rpm N
vacuum_pressure_kpa N
vibration_more_than N
noise_level_db N
max_travelling_speed_ms N
max_operation_speed_ms N
sweep_efficiency_sqm_h N
scrub_efficiency_sqm_h N
mop_efficiency_sqm_h N
sweep_scrub_efficiency_sqm_h N
vacuum_efficiency_sqm_h N
cleaning_capacity_l N
waste_capacity_l N
trash_capacity_l N
dust_bag_capacity_l N
dust_bag_charging_dock Y
auto_clean Y
max_output_power_w N
drive_motor_power_w N
battery_type T
battery_voltage_v N
battery_capacity_ah N
battery_charging_time_hr N
sweep_work_time_hr N
scrub_work_time_hr N
mop_work_time_hr N
min_pass_width_mm N
min_pass_height_mm N
max_vertical_obstacle_height_mm N
max_trench_clearance_mm N
max_narrow_mm N
min_u_turn_width_mm N
min_edge_from_wall_mm N
max_step_height_mm N
slope_angle_auto_deg N
sensor_distance_m N
sensor_degree_deg N
ip_rating T
hepa T
min_operating_temp_c N
max_operating_temp_c N
min_operating_humidity_pct N
max_operating_humidity_pct N
application B
outdoor B
indoor B
fn_sweep_no_vacuum B
fn_sweep_vacuum B
fn_mop_dry B
fn_mop_wet B
fn_scrub_brush_roller B
fn_scrub_brush_disc B
nav_2d_lidar B
nav_3d_lidar B
nav_vslam B
sensor_rgb B
sensor_rgbd B
sensor_ultrasonic B
anti_collision B
anti_drop B
spot_ai B
manual_drive B
multi_robot_connect B
auto_task_switch B
iot_integration B
work_station B
dock_charge B
manual_charge B
floor_layout_method B
floor_rough_surface B
floor_smooth_surface B
floor_real_wood B
floor_paving_blocks B
floor_granite_tiles B
floor_ceramic_tiles B
floor_marble B
floor_terrazzo B
floor_nature_stone B
floor_terracotta B
floor_smooth_concrete B
floor_coarse_concrete B
floor_stamped_concrete B
floor_epoxy B
floor_pu B
floor_asphalt B
floor_short_carpet B
floor_long_carpet B
floor_spc B
floor_wpc B
floor_laminate B
floor_vinyl_pvc B
floor_washed_sand B
EOF
)

# Build the ordered letter list C..CY (101 entries).
letters=()
for a in "" A B C; do
  for b in A B C D E F G H I J K L M N O P Q R S T U V W X Y Z; do
    letters+=("$a$b")
  done
done
# letters currently A..Z, AA..AZ, BA..BZ, CA..CZ — slice C..CY
ordered=()
started=0
for L in "${letters[@]}"; do
  [ "$L" = "C" ] && started=1
  [ $started -eq 1 ] && ordered+=("$L")
  [ "$L" = "CY" ] && break
done

mapfile -t maplines <<< "$MAP"
if [ ${#ordered[@]} -ne ${#maplines[@]} ]; then
  echo "MAPPING MISMATCH: ${#ordered[@]} letters vs ${#maplines[@]} columns" >&2
  exit 1
fi

cell() { awk -F'|' -v r="$1" -v c="$2" '$1==r && $2==c{print $3; found=1} END{if(!found) print ""}' "$DEC"; }

sqlnum() { [ -z "$1" ] || [ "$1" = "N/A" ] && echo "NULL" || echo "$1"; }
sqltext() { [ -z "$1" ] || [ "$1" = "N/A" ] && echo "NULL" || echo "'$(echo "$1" | sed "s/'/''/g")'"; }
sqlyn()  { case "$1" in Yes|yes|YES) echo TRUE;; No|no|NO) echo FALSE;; *) echo NULL;; esac; }
sqlbin() { case "$1" in 1) echo TRUE;; 0) echo FALSE;; *) echo NULL;; esac; }

{
echo "-- Gausium cleaning-robot catalogue + specs, generated from"
echo "-- raaspal-rims/docs/DataSheetGS_filled_07152026.xlsx"
echo "-- Idempotent: safe to re-run. Brand typo 'Guasium' normalised to 'Gausium'."
echo "-- 'N/A' in the sheet becomes NULL. New models land as DRAFT so"
echo "-- RecommendationService (which filters on VERIFIED) will not recommend them."
echo
echo "BEGIN;"
echo

for row in $(seq 3 14); do
  brand=$(cell "$row" A); model=$(cell "$row" B)
  [ -z "$model" ] && continue
  [ "$brand" = "Guasium" ] && brand="Gausium"
  eb=$(echo "$brand" | sed "s/'/''/g"); em=$(echo "$model" | sed "s/'/''/g")

  echo "-- ── $brand $model ─────────────────────────────────────────────"
  echo "INSERT INTO robots (brand, model, robot_type, test_status)"
  echo "SELECT '$eb', '$em', 'CLEANING', 'DRAFT'"
  echo "WHERE NOT EXISTS (SELECT 1 FROM robots"
  echo "                  WHERE lower(brand) = lower('$eb') AND lower(model) = lower('$em'));"
  echo

  cols=("robot_id"); vals=("r.id")
  for i in "${!ordered[@]}"; do
    L="${ordered[$i]}"
    set -- ${maplines[$i]}
    dbcol="$1"; typ="$2"
    raw=$(cell "$row" "$L")
    case "$typ" in
      N) v=$(sqlnum "$raw");;
      T) v=$(sqltext "$raw");;
      Y) v=$(sqlyn "$raw");;
      B) v=$(sqlbin "$raw");;
    esac
    cols+=("$dbcol"); vals+=("$v")
  done

  echo "INSERT INTO robot_specs_cleaning ($(IFS=,; echo "${cols[*]}"))"
  echo "SELECT $(IFS=,; echo "${vals[*]}")"
  echo "FROM robots r"
  echo "WHERE lower(r.brand) = lower('$eb') AND lower(r.model) = lower('$em')"
  echo "  AND NOT EXISTS (SELECT 1 FROM robot_specs_cleaning s WHERE s.robot_id = r.id);"
  echo
done

echo "COMMIT;"
} > "$OUT"

echo "written: $OUT ($(wc -l < "$OUT") lines)"
